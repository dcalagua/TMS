import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Controller, useFieldArray, useForm, useWatch } from "react-hook-form";
import { Alert, Box, Button, IconButton, MenuItem, TextField, Tooltip, Typography } from "@mui/material";
import { AddRounded, CheckRounded, DeleteOutlineRounded, ReceiptLongRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import { fetchTrips } from "../../shared/api/planningApi";
import {
  CHARGE_CODES, DOCUMENT_KINDS, PENALTY_REFERENCE_TYPES, fetchFiscalSettings, fetchInvoices, receiveInvoice,
  type CarrierInvoiceRequest, type CarrierInvoiceView, type ChargeCode, type DocumentKind,
  type PenaltyReferenceType, type TaxRegimeView,
} from "../../shared/api/settlementApi";
import {
  breakdownAddsUp, documentCodeOf, isAllowedVatRate, isValidDocumentNumber, isValidTaxId, previewVatRate,
  previewWithholdings, requiresBreakdown as breakdownRequiredFor, taxOf, type WithholdingPreview,
} from "./fiscalRules";
import {
  FormDateInput, FormDrawer, FormRow, FormSection, LookupField, type LookupOption,
} from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDecimal, today } from "../../lib/locale";

const FORM_ID = "carrier-invoice-form";
const CURRENCY_PATTERN = /^[A-Za-z]{3}$/;
const TRIP_LOOKUP_SIZE = 20;

interface LineValues {
  /** La opción elegida entera y no sólo su id: el lookup necesita pintar el envío elegido. */
  trip: LookupOption | null;
  chargeCode: ChargeCode;
  description: string;
  quantity: string;
  unitAmount: string;
  lineAmount: string;
  /** Sólo en una línea PENALTY (V66): el motivo es obligatorio y el importe, negativo. */
  penaltyReason: string;
  penaltyReferenceType: PenaltyReferenceType | "";
}

interface InvoiceFormValues {
  carrierId: string;
  invoiceNumber: string;
  invoiceDate: string;
  currency: string;
  totalAmount: string;
  externalReference: string;
  notes: string;
  lines: LineValues[];
  // ADR-027: lo fiscal, en el vocabulario del régimen de la empresa.
  documentKind: DocumentKind;
  referencedInvoiceId: string;
  documentNumber: string;
  issuerTaxId: string;
  subtotalAmount: string;
  taxRate: string;
  taxAmount: string;
}

const EMPTY_LINE: LineValues = {
  trip: null, chargeCode: "FREIGHT", description: "", quantity: "", unitAmount: "", lineAmount: "",
  penaltyReason: "", penaltyReferenceType: "",
};

const KNOWN_FIELDS = new Set<string>([
  "carrierId", "invoiceNumber", "invoiceDate", "currency", "totalAmount", "externalReference", "notes",
  "documentKind", "referencedInvoiceId", "documentNumber", "issuerTaxId", "subtotalAmount", "taxRate",
  "taxAmount",
]);

/** Sin régimen todavía (o sin permiso para leerlo): el impuesto se llama por su nombre genérico. */
const FALLBACK_VAT_LABEL = "IVA/VAT";

const toNumber = (value: string): number | null => (value.trim() === "" ? null : Number(value));

/** Un número válido o vacío. `negative` admite la deducción de una línea PENALTY. */
function numeric(value: string, { negative = false } = {}): true | string {
  if (value.trim() === "") return true;
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return t("Introduce un número válido");
  if (!negative && parsed < 0) return t("No puede ser negativo");
  return true;
}

/** Lo que el servidor calculará, dicho en una frase por retención. */
function previewSentences(preview: WithholdingPreview, regime: TaxRegimeView, currency: string): string[] {
  if (preview.status === "none") {
    if (preview.reason === "note") return [t("Una nota no calcula retenciones propias.")];
    if (preview.reason === "no-rules") return [t("El régimen fiscal de la empresa no calcula retenciones.")];
    return regime.fiscalCurrency
      ? [t("Las retenciones sólo se calculan en {{currency}}.", { currency: regime.fiscalCurrency })]
      : [t("El régimen fiscal de la empresa no calcula retenciones.")];
  }
  const sentences = preview.items.map((item) => t("{{label}}{{code}} al {{rate}} %: {{amount}} {{currency}} (se calcula, no se paga).", {
    label: item.label,
    code: item.code ? ` ${item.code}` : "",
    rate: fmtDecimal(item.rate, 2),
    amount: fmtDecimal(item.amount, 2),
    currency,
  }));
  for (const skip of preview.skipped) {
    if (skip.reason === "below-threshold" && skip.rule.thresholdAmount !== null) {
      sentences.push(t("Sin {{label}}: el total no supera {{threshold}} {{currency}}.", {
        label: skip.rule.label, threshold: fmtDecimal(skip.rule.thresholdAmount, 2), currency,
      }));
    }
  }
  if (sentences.length === 0) sentences.push(t("No aplica ninguna retención a este documento."));
  return sentences;
}

interface InvoiceFormDrawerProps {
  companyId: string;
  onClose: () => void;
  /** Recibe la factura recién registrada, para abrirla sin un segundo clic. */
  onSaved: (invoice: CarrierInvoiceView) => void;
}

/**
 * Registrar una factura de transportista tal como llegó (migración V46, fase 50).
 *
 * <h2>Lo que se compara es la cabecera</h2>
 * El total de la cabecera es lo que la comparación enfrenta con lo esperado, **no** la suma de las
 * líneas. El documento del transportista es el que es: un redondeo o un descuento en cabecera los
 * hacen discrepar con razón, así que la pantalla enseña la suma como ayuda y no la impone.
 *
 * <h2>El código de cargo</h2>
 * Cada línea dice de qué componente del flete habla. Varias líneas del mismo viaje con códigos
 * distintos se suman; la misma combinación viaje + cargo dos veces es un duplicado, y se avisa
 * aquí antes de que lo diga el servidor.
 *
 * <h2>Lo fiscal (ADR-027)</h2>
 * Todo sale del régimen fiscal de la empresa que devuelve el servidor (Perú, Ecuador o genérico):
 * el nombre del impuesto, las clases de comprobante con su código del país, el formato del número,
 * la identificación del emisor y su regla, las tasas admitidas y las retenciones. La pantalla no
 * trae ninguna tasa, código ni moneda propia. Las líneas van **sin impuesto**: lo que se compara con
 * el costo del viaje es el subtotal.
 */
export function InvoiceFormDrawer({ companyId, onClose, onSaved }: InvoiceFormDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-invoice-form", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "businessName,asc", signal }),
  });

  const regimeQuery = useQuery({
    queryKey: ["fiscal-settings", companyId],
    queryFn: ({ signal }) => fetchFiscalSettings(companyId, signal),
  });

  const {
    register, control, handleSubmit, setError, setValue, getValues,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<InvoiceFormValues>({
    defaultValues: {
      carrierId: "",
      invoiceNumber: "",
      invoiceDate: today(),
      currency: "",
      totalAmount: "",
      externalReference: "",
      notes: "",
      lines: [{ ...EMPTY_LINE }],
      documentKind: "INVOICE",
      referencedInvoiceId: "",
      documentNumber: "",
      issuerTaxId: "",
      subtotalAmount: "",
      taxRate: "",
      taxAmount: "",
    },
  });

  // La moneda fiscal y la tasa de flete del régimen, en cuanto llegan, si nadie escribió otras.
  const regime = regimeQuery.data;
  useEffect(() => {
    if (!regime) return;
    if (getValues("currency") === "" && regime.fiscalCurrency) setValue("currency", regime.fiscalCurrency);
    const rate = previewVatRate(regime);
    if (getValues("taxRate") === "" && rate !== null) setValue("taxRate", String(rate));
  }, [regime, getValues, setValue]);

  const { fields, append, remove } = useFieldArray({ control, name: "lines" });
  const carrierId = useWatch({ control, name: "carrierId" });
  const currency = useWatch({ control, name: "currency" });
  const lines = useWatch({ control, name: "lines" });
  const documentKind = useWatch({ control, name: "documentKind" });
  const documentNumber = useWatch({ control, name: "documentNumber" });
  const totalAmount = useWatch({ control, name: "totalAmount" });
  const subtotalAmount = useWatch({ control, name: "subtotalAmount" });
  const taxAmount = useWatch({ control, name: "taxAmount" });
  const isNote = documentKind !== "INVOICE";
  const requiresBreakdown = breakdownRequiredFor(regime, currency ?? "");
  const vatLabel = regime?.vatLabel ?? FALLBACK_VAT_LABEL;
  const taxIdLabel = regime?.taxId.label ?? t("Identificación fiscal");

  // Las facturas de este transportista que una nota puede corregir: no notas, no rechazadas.
  const referencedQuery = useQuery({
    queryKey: ["invoices-for-note", companyId, carrierId],
    enabled: isNote && Boolean(carrierId),
    queryFn: ({ signal }) => fetchInvoices(companyId, { carrierId, size: 100 }, signal),
  });
  const referenceable = (referencedQuery.data?.content ?? [])
    .filter((invoice) => (invoice.documentKind ?? "INVOICE") === "INVOICE" && invoice.status !== "REJECTED");

  const total = Number(totalAmount);
  const withholdingPreview = regime && totalAmount?.trim() !== "" && Number.isFinite(total)
    ? previewWithholdings(regime, documentKind, currency ?? "", {
      subtotal: toNumber(subtotalAmount ?? ""), tax: toNumber(taxAmount ?? ""), total,
    })
    : null;

  /** Llena el impuesto con la tasa y el total con subtotal + impuesto. */
  function computeBreakdown() {
    const subtotal = Number(getValues("subtotalAmount"));
    const rate = Number(getValues("taxRate"));
    if (!Number.isFinite(subtotal) || !Number.isFinite(rate) || getValues("subtotalAmount").trim() === "") return;
    const tax = taxOf(subtotal, rate);
    setValue("taxAmount", tax.toFixed(2), { shouldDirty: true, shouldValidate: true });
    setValue("totalAmount", (subtotal + tax).toFixed(2), { shouldDirty: true, shouldValidate: true });
  }

  // Ayuda, no regla: la suma de las líneas al lado del total que se va a comparar.
  const linesSum = (lines ?? []).reduce((sum, line) => {
    const value = Number(line?.lineAmount);
    return Number.isFinite(value) ? sum + value : sum;
  }, 0);

  // El aviso adelantado de lo que el servidor llamará duplicado: el mismo viaje y el mismo cargo.
  const duplicateKeys = new Set<string>();
  const seen = new Set<string>();
  for (const line of lines ?? []) {
    if (!line?.trip) continue;
    const key = `${line.trip.id}|${line.chargeCode}`;
    if (seen.has(key)) duplicateKeys.add(key);
    seen.add(key);
  }

  async function searchTrips(term: string, signal: AbortSignal): Promise<LookupOption[]> {
    const page = await fetchTrips({
      companyId, size: TRIP_LOOKUP_SIZE, signal,
      shipmentNumber: term || undefined,
      // Sólo los envíos de este transportista: facturar el de otro es el error que se quiere evitar.
      carrierId: carrierId || undefined,
    });
    return page.content.map((trip) => ({
      id: trip.id,
      code: trip.shipmentNumber,
      name: [fmtDate(trip.planningDate), trip.carrierName].filter(Boolean).join(" · "),
    }));
  }

  async function onSubmit(values: InvoiceFormValues) {
    setFormError(null);
    const hasFiscalIdentity = values.documentNumber.trim() !== "";
    const hasBreakdown = values.subtotalAmount.trim() !== "";
    const request: CarrierInvoiceRequest = {
      carrierId: values.carrierId,
      invoiceNumber: values.invoiceNumber.trim() || null,
      invoiceDate: values.invoiceDate,
      currency: values.currency.trim().toUpperCase(),
      totalAmount: Number(values.totalAmount),
      externalReference: values.externalReference.trim() || null,
      notes: values.notes.trim() || null,
      lines: values.lines.map((line) => ({
        tripId: line.trip?.id ?? null,
        chargeCode: line.chargeCode,
        description: line.description.trim(),
        quantity: toNumber(line.quantity),
        unitAmount: toNumber(line.unitAmount),
        lineAmount: Number(line.lineAmount),
        ...(line.chargeCode === "PENALTY"
          ? { penaltyReason: line.penaltyReason.trim(), penaltyReferenceType: line.penaltyReferenceType || null }
          : {}),
      })),
      documentKind: values.documentKind,
      ...(isNote ? { referencedInvoiceId: values.referencedInvoiceId } : {}),
      ...(hasFiscalIdentity ? {
        documentNumber: values.documentNumber.trim().toUpperCase(),
        issuerTaxId: values.issuerTaxId.trim(),
      } : {}),
      ...(hasBreakdown ? {
        subtotalAmount: Number(values.subtotalAmount),
        taxRate: Number(values.taxRate),
        taxAmount: Number(values.taxAmount),
      } : {}),
    };

    try {
      const created = await receiveInvoice(companyId, request);
      onSaved(created);
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={<ReceiptLongRounded />}
      title={t("Registrar factura")}
      subtitle={t("La factura del transportista tal como llegó. Después se compara con lo que TMS esperaba.")}
      size="lg"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting} color="inherit" sx={{ color: "text.secondary" }}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : t("Registrar factura")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 1 }}>{formError}</Alert>}
        {regimeQuery.isError && (
          <Alert severity="warning" variant="outlined" sx={{ mb: 1 }}>
            {t("No se pudo cargar el régimen fiscal de la empresa: el servidor validará los impuestos al guardar.")}
          </Alert>
        )}

        <FormSection title={t("Cabecera")} help={t("Quién factura, con qué número y por cuánto. El total de la cabecera es lo que se compara.")}>
          <FormRow>
            <Controller
              control={control}
              name="documentKind"
              render={({ field }) => (
                <TextField
                  select label={t("Tipo de comprobante")} size="small" fullWidth
                  value={field.value} onChange={(e) => field.onChange(e.target.value as DocumentKind)}
                  helperText={isNote ? t("Una nota corrige una factura del mismo transportista; sus importes van en positivo.") : undefined}
                >
                  {DOCUMENT_KINDS.map((kind) => {
                    const code = documentCodeOf(regime, kind);
                    return (
                      <MenuItem key={kind} value={kind}>
                        {code ? `${enumLabel("documentKind", kind)} (${code})` : enumLabel("documentKind", kind)}
                      </MenuItem>
                    );
                  })}
                </TextField>
              )}
            />
            {isNote ? (
              <Controller
                control={control}
                name="referencedInvoiceId"
                rules={{ validate: (value) => !isNote || value !== "" || t("Una nota indica la factura que corrige.") }}
                render={({ field }) => (
                  <TextField
                    select label={t("Factura que corrige")} required size="small" fullWidth
                    value={field.value} onChange={(e) => field.onChange(e.target.value)}
                    error={Boolean(errors.referencedInvoiceId)}
                    helperText={errors.referencedInvoiceId?.message
                      ?? (carrierId ? undefined : t("Elige primero el transportista."))}
                  >
                    <MenuItem value="">{t("Selecciona una factura")}</MenuItem>
                    {referenceable.map((invoice) => (
                      <MenuItem key={invoice.id} value={invoice.id}>
                        {`${invoice.invoiceNumber} · ${fmtDate(invoice.invoiceDate)} · ${fmtDecimal(invoice.totalAmount, 2)} ${invoice.currency}`}
                      </MenuItem>
                    ))}
                  </TextField>
                )}
              />
            ) : <Box />}
          </FormRow>
          <FormRow>
            <TextField
              label={t("Número del comprobante")} size="small" fullWidth
              placeholder={regime?.documentNumber.placeholder ?? undefined}
              error={Boolean(errors.documentNumber)}
              helperText={errors.documentNumber?.message
                ?? regime?.documentNumber.hint
                ?? t("El número fiscal y la identificación del emisor van juntos.")}
              {...register("documentNumber", {
                validate: (value, all) => {
                  if (value.trim() === "" && all.issuerTaxId.trim() === "") return true;
                  if (value.trim() === "") return t("El número del comprobante y la identificación del emisor van juntos.");
                  if (!regime || isValidDocumentNumber(regime, value)) return true;
                  return regime.documentNumber.placeholder
                    ? t("No tiene el formato del país, p. ej. {{example}}.", { example: regime.documentNumber.placeholder })
                    : t("No tiene el formato del país.");
                },
              })}
            />
            <TextField
              label={t("{{label}} emisor", { label: taxIdLabel })} size="small" fullWidth
              error={Boolean(errors.issuerTaxId)} helperText={errors.issuerTaxId?.message ?? regime?.taxId.hint ?? undefined}
              {...register("issuerTaxId", {
                validate: (value, all) => {
                  if (all.documentNumber.trim() === "" && value.trim() === "") return true;
                  if (value.trim() === "") return t("El número del comprobante y la identificación del emisor van juntos.");
                  if (!isValidTaxId(regime?.taxId.rule ?? "FREE_TEXT", value)) {
                    return regime?.taxId.rule === "FREE_TEXT" || !regime
                      ? t("Identificación fiscal no válida.")
                      : t("{{label}} no válido: el dígito verificador no coincide.", { label: taxIdLabel });
                  }
                  return true;
                },
              })}
            />
          </FormRow>
          <FormRow>
            <Controller
              control={control}
              name="carrierId"
              rules={{ required: t("Este campo es obligatorio") }}
              render={({ field }) => (
                <TextField
                  select label={t("Transportista")} required size="small" fullWidth
                  value={field.value} onChange={(e) => field.onChange(e.target.value)}
                  error={Boolean(errors.carrierId)}
                  helperText={errors.carrierId?.message
                    ?? (carriersQuery.isError ? t("No se pudo cargar la lista de transportistas.") : undefined)}
                >
                  <MenuItem value="">{t("Selecciona un transportista")}</MenuItem>
                  {(carriersQuery.data?.content ?? []).map((carrier) => (
                    <MenuItem key={carrier.id} value={carrier.id}>{carrier.businessName}</MenuItem>
                  ))}
                </TextField>
              )}
            />
            <TextField
              label={t("Número de factura")} required={documentNumber.trim() === ""} size="small" fullWidth
              error={Boolean(errors.invoiceNumber)}
              helperText={errors.invoiceNumber?.message
                ?? (documentNumber.trim() !== "" ? t("Con el número del comprobante se compone solo.") : undefined)}
              {...register("invoiceNumber", {
                validate: (value, all) => all.documentNumber.trim() !== "" || value.trim() !== "" || t("Este campo es obligatorio"),
                maxLength: { value: 60, message: t("No puede superar los {{count}} caracteres", { count: 60 }) },
              })}
            />
          </FormRow>
          <FormRow cols={3}>
            <FormDateInput
              control={control} name="invoiceDate" mode="date"
              rules={{ required: t("Este campo es obligatorio") }}
              label={t("Fecha de factura")} required size="small" fullWidth
              error={Boolean(errors.invoiceDate)} helperText={errors.invoiceDate?.message}
            />
            <TextField
              label={t("Moneda")} required size="small" fullWidth placeholder={regime?.fiscalCurrency ?? "USD"}
              error={Boolean(errors.currency)} helperText={errors.currency?.message}
              {...register("currency", {
                required: t("Este campo es obligatorio"),
                pattern: { value: CURRENCY_PATTERN, message: t("Tres letras, código ISO 4217 (p. ej. USD).") },
              })}
            />
            <TextField
              label={t("Importe total")} required size="small" fullWidth type="number"
              error={Boolean(errors.totalAmount)} helperText={errors.totalAmount?.message}
              {...register("totalAmount", {
                required: t("Este campo es obligatorio"),
                validate: (value, all) => {
                  const valid = numeric(value);
                  if (valid !== true) return valid;
                  if (all.subtotalAmount.trim() === "" || all.taxAmount.trim() === "") return true;
                  return breakdownAddsUp(Number(all.subtotalAmount), Number(all.taxAmount), Number(value),
                    regime?.breakdownTolerance ?? 0.01)
                    || t("Subtotal + {{vat}} no coincide con el total.", { vat: vatLabel });
                },
              })}
            />
          </FormRow>
          <FormRow>
            <TextField
              label={t("Referencia externa")} size="small" fullWidth
              error={Boolean(errors.externalReference)} helperText={errors.externalReference?.message}
              {...register("externalReference", {
                maxLength: { value: 120, message: t("No puede superar los {{count}} caracteres", { count: 120 }) },
              })}
            />
            <TextField
              label={t("Notas")} size="small" fullWidth
              error={Boolean(errors.notes)} helperText={errors.notes?.message}
              {...register("notes", {
                maxLength: { value: 1000, message: t("No puede superar los {{count}} caracteres", { count: 1000 }) },
              })}
            />
          </FormRow>
        </FormSection>

        <FormSection
          title={t("Impuestos")}
          help={requiresBreakdown
            ? t("Subtotal sin {{vat}}, tasa e importe; obligatorio en {{currency}}. Las retenciones las calcula el servidor con las reglas de la empresa.", {
              vat: vatLabel, currency: regime?.fiscalCurrency ?? "",
            })
            : t("Subtotal sin {{vat}}, tasa e importe, juntos o ninguno. Las retenciones las calcula el servidor con las reglas de la empresa.", { vat: vatLabel })}
          actions={
            <Button size="small" onClick={computeBreakdown}>{t("Calcular {{vat}} y total", { vat: vatLabel })}</Button>
          }
        >
          <FormRow cols={3}>
            <TextField
              label={t("Subtotal (sin {{vat}})", { vat: vatLabel })} required={requiresBreakdown} size="small" fullWidth type="number"
              error={Boolean(errors.subtotalAmount)} helperText={errors.subtotalAmount?.message}
              {...register("subtotalAmount", {
                validate: (value, all) => {
                  const valid = numeric(value);
                  if (valid !== true) return valid;
                  const anyPart = [value, all.taxAmount].some((part) => part.trim() !== "");
                  if (requiresBreakdown && value.trim() === "") {
                    return t("En {{currency}} el documento indica su desglose.", { currency: regime?.fiscalCurrency ?? "" });
                  }
                  return !anyPart || (value.trim() !== "" && all.taxAmount.trim() !== "")
                    || t("Subtotal, tasa e impuesto van juntos.");
                },
              })}
            />
            <TextField
              label={t("Tasa {{vat}} (%)", { vat: vatLabel })} size="small" fullWidth type="number"
              error={Boolean(errors.taxRate)}
              helperText={errors.taxRate?.message
                ?? (regime && regime.allowedVatRates.length > 0
                  ? t("Tasas admitidas: {{rates}} %", { rates: regime.allowedVatRates.map((rate) => fmtDecimal(rate, 2)).join(", ") })
                  : undefined)}
              {...register("taxRate", {
                validate: (value, all) => {
                  const valid = numeric(value);
                  if (valid !== true) return valid;
                  if (value.trim() === "" || all.subtotalAmount.trim() === "" || !regime) return true;
                  return isAllowedVatRate(regime, Number(value))
                    || t("Tasa no admitida por el régimen fiscal: {{rates}} %.", {
                      rates: regime.allowedVatRates.map((rate) => fmtDecimal(rate, 2)).join(", "),
                    });
                },
              })}
            />
            <TextField
              label={vatLabel} required={requiresBreakdown} size="small" fullWidth type="number"
              error={Boolean(errors.taxAmount)} helperText={errors.taxAmount?.message}
              {...register("taxAmount", {
                validate: (value) => {
                  const valid = numeric(value);
                  if (valid !== true) return valid;
                  return !requiresBreakdown || value.trim() !== ""
                    || t("En {{currency}} el documento indica su desglose.", { currency: regime?.fiscalCurrency ?? "" });
                },
              })}
            />
          </FormRow>
          {regime && withholdingPreview && (
            <Alert severity="info" variant="outlined" sx={{ py: 0 }}>
              {previewSentences(withholdingPreview, regime, (currency ?? "").toUpperCase()).map((sentence) => (
                <Box key={sentence}>{sentence}</Box>
              ))}
            </Alert>
          )}
        </FormSection>

        <FormSection
          title={t("Líneas")}
          help={t("Varias líneas del mismo viaje con distinto código de cargo se suman; la misma combinación viaje + cargo dos veces es duplicado.")}
          actions={
            <Button size="small" startIcon={<AddRounded />} onClick={() => append({ ...EMPTY_LINE })}>
              {t("Añadir línea")}
            </Button>
          }
        >
          <Box sx={{ display: "grid", gap: 1.5 }}>
            {fields.map((item, index) => {
              const line = lines?.[index];
              const duplicated = line?.trip ? duplicateKeys.has(`${line.trip.id}|${line.chargeCode}`) : false;
              const lineErrors = errors.lines?.[index];
              return (
                <Box
                  key={item.id}
                  sx={{
                    p: 1.5, border: "1px solid", borderColor: duplicated ? "warning.main" : "divider",
                    borderRadius: 1, display: "grid", gap: 1,
                  }}
                >
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                    <Typography variant="caption" sx={{ fontWeight: 800, color: "text.secondary" }}>
                      {t("Línea {{n}}", { n: index + 1 })}
                    </Typography>
                    <Box sx={{ flex: 1 }} />
                    <Tooltip title={t("Quitar línea")}>
                      <span>
                        <IconButton
                          size="small" aria-label={t("Quitar línea {{n}}", { n: index + 1 })}
                          disabled={fields.length === 1} onClick={() => remove(index)}
                        >
                          <DeleteOutlineRounded fontSize="small" />
                        </IconButton>
                      </span>
                    </Tooltip>
                  </Box>
                  <FormRow>
                    <Controller
                      control={control}
                      name={`lines.${index}.trip`}
                      render={({ field }) => (
                        <LookupField
                          label={t("Envío")}
                          value={field.value?.id ?? ""}
                          selected={field.value}
                          onChange={(option) => field.onChange(option)}
                          search={searchTrips}
                          queryKey={["invoice-trip-lookup", companyId, carrierId]}
                          placeholder={t("Número de envío")}
                          helperText={t("Opcional: un cargo que no es de un envío concreto va sin él.")}
                        />
                      )}
                    />
                    <Controller
                      control={control}
                      name={`lines.${index}.chargeCode`}
                      render={({ field }) => (
                        <TextField
                          select label={t("Código de cargo")} size="small" fullWidth
                          value={field.value} onChange={(e) => field.onChange(e.target.value as ChargeCode)}
                          helperText={field.value === "PENALTY" ? t("Importe negativo y motivo; el aprobador la acepta aparte.") : undefined}
                        >
                          {CHARGE_CODES.map((code) => (
                            <MenuItem key={code} value={code}>{enumLabel("chargeCode", code)}</MenuItem>
                          ))}
                        </TextField>
                      )}
                    />
                  </FormRow>
                  <TextField
                    label={t("Descripción")} required size="small" fullWidth
                    error={Boolean(lineErrors?.description)} helperText={lineErrors?.description?.message}
                    {...register(`lines.${index}.description`, {
                      required: t("Este campo es obligatorio"),
                      maxLength: { value: 300, message: t("No puede superar los {{count}} caracteres", { count: 300 }) },
                    })}
                  />
                  <FormRow cols={3}>
                    <TextField
                      label={t("Cantidad")} size="small" fullWidth type="number"
                      error={Boolean(lineErrors?.quantity)} helperText={lineErrors?.quantity?.message}
                      {...register(`lines.${index}.quantity`, {
                        validate: (value, all) => {
                          const valid = numeric(value);
                          if (valid !== true) return valid;
                          // Los dos o ninguno: una cantidad sin precio unitario no explica nada.
                          const unit = all.lines[index]?.unitAmount ?? "";
                          return (value.trim() === "") === (unit.trim() === "")
                            || t("Cantidad y precio unitario van juntos o ninguno.");
                        },
                      })}
                    />
                    <TextField
                      label={t("Precio unitario")} size="small" fullWidth type="number"
                      error={Boolean(lineErrors?.unitAmount)} helperText={lineErrors?.unitAmount?.message}
                      {...register(`lines.${index}.unitAmount`, {
                        validate: (value, all) => {
                          const valid = numeric(value);
                          if (valid !== true) return valid;
                          const quantity = all.lines[index]?.quantity ?? "";
                          return (value.trim() === "") === (quantity.trim() === "")
                            || t("Cantidad y precio unitario van juntos o ninguno.");
                        },
                      })}
                    />
                    <TextField
                      label={t("Importe de la línea (sin {{vat}})", { vat: vatLabel })} required size="small" fullWidth type="number"
                      error={Boolean(lineErrors?.lineAmount)} helperText={lineErrors?.lineAmount?.message}
                      {...register(`lines.${index}.lineAmount`, {
                        required: t("Este campo es obligatorio"),
                        validate: (value, all) => {
                          const penalty = all.lines[index]?.chargeCode === "PENALTY";
                          const valid = numeric(value, { negative: penalty });
                          if (valid !== true) return valid;
                          return !penalty || Number(value) < 0 || t("Una penalidad es una deducción: importe negativo.");
                        },
                      })}
                    />
                  </FormRow>
                  {line?.chargeCode === "PENALTY" && (
                    <FormRow>
                      <TextField
                        label={t("Motivo de la penalidad")} required size="small" fullWidth
                        error={Boolean(lineErrors?.penaltyReason)} helperText={lineErrors?.penaltyReason?.message}
                        {...register(`lines.${index}.penaltyReason`, {
                          validate: (value, all) => all.lines[index]?.chargeCode !== "PENALTY" || value.trim() !== ""
                            || t("Una penalidad dice por qué se descuenta."),
                          maxLength: { value: 500, message: t("No puede superar los {{count}} caracteres", { count: 500 }) },
                        })}
                      />
                      <Controller
                        control={control}
                        name={`lines.${index}.penaltyReferenceType`}
                        render={({ field }) => (
                          <TextField
                            select label={t("Referencia")} size="small" fullWidth
                            value={field.value} onChange={(e) => field.onChange(e.target.value)}
                          >
                            <MenuItem value="">{t("Sin referencia")}</MenuItem>
                            {PENALTY_REFERENCE_TYPES.map((code) => (
                              <MenuItem key={code} value={code}>{enumLabel("penaltyReferenceType", code)}</MenuItem>
                            ))}
                          </TextField>
                        )}
                      />
                    </FormRow>
                  )}
                  {duplicated && (
                    <Typography variant="caption" color="warning.main">
                      {t("Este viaje ya tiene otra línea con el mismo código de cargo: se tratará como duplicado.")}
                    </Typography>
                  )}
                </Box>
              );
            })}
            <Typography variant="caption" color="text.secondary" sx={{ fontVariantNumeric: "tabular-nums" }}>
              {t("Suma de las líneas: {{sum}} {{currency}}. Lo que se compara es el subtotal (o el total sin desglose).", {
                sum: fmtDecimal(linesSum, 2), currency: (currency ?? "").toUpperCase(),
              })}
            </Typography>
          </Box>
        </FormSection>
      </Box>
    </FormDrawer>
  );
}
