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
  CHARGE_CODES, DOCUMENT_TYPES, PENALTY_REFERENCE_TYPES, fetchFiscalSettings, fetchInvoices, receiveInvoice,
  type CarrierInvoiceRequest, type CarrierInvoiceView, type ChargeCode, type DocumentTypeCode,
  type PenaltyReferenceType,
} from "../../shared/api/settlementApi";
import { breakdownAddsUp, igvOf, isValidRuc, previewTaxes } from "./fiscalRules";
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
  // V66 (ADR-024 parte 2).
  documentType: DocumentTypeCode;
  referencedInvoiceId: string;
  series: string;
  correlative: string;
  issuerRuc: string;
  subtotalAmount: string;
  igvRate: string;
  igvAmount: string;
}

const EMPTY_LINE: LineValues = {
  trip: null, chargeCode: "FREIGHT", description: "", quantity: "", unitAmount: "", lineAmount: "",
  penaltyReason: "", penaltyReferenceType: "",
};

const KNOWN_FIELDS = new Set<string>([
  "carrierId", "invoiceNumber", "invoiceDate", "currency", "totalAmount", "externalReference", "notes",
  "documentType", "referencedInvoiceId", "series", "correlative", "issuerRuc", "subtotalAmount", "igvRate",
  "igvAmount",
]);

const SERIES_PATTERN = /^(F[A-Z0-9]{3}|E001|[0-9]{4})$/;
const CORRELATIVE_PATTERN = /^[0-9]{1,8}$/;

const toNumber = (value: string): number | null => (value.trim() === "" ? null : Number(value));

/** Un número válido o vacío. `negative` admite la deducción de una línea PENALTY. */
function numeric(value: string, { negative = false } = {}): true | string {
  if (value.trim() === "") return true;
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return t("Introduce un número válido");
  if (!negative && parsed < 0) return t("No puede ser negativo");
  return true;
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
 * <h2>Lo fiscal (V66)</h2>
 * Tipo SUNAT (factura, nota de crédito o de débito, que enlaza la factura que corrige), serie,
 * correlativo y RUC emisor (con su dígito verificador), y el desglose subtotal + IGV = total, que
 * es obligatorio en PEN. Las líneas van **sin IGV**: lo que se compara con el costo del viaje es el
 * subtotal. La detracción o la retención se calculan con las reglas de la empresa que devuelve el
 * servidor; la pantalla no trae ningún porcentaje propio.
 */
export function InvoiceFormDrawer({ companyId, onClose, onSaved }: InvoiceFormDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-invoice-form", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "businessName,asc", signal }),
  });

  const fiscalQuery = useQuery({
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
      currency: "PEN",
      totalAmount: "",
      externalReference: "",
      notes: "",
      lines: [{ ...EMPTY_LINE }],
      documentType: "01",
      referencedInvoiceId: "",
      series: "",
      correlative: "",
      issuerRuc: "",
      subtotalAmount: "",
      igvRate: "",
      igvAmount: "",
    },
  });

  // La tasa de IGV de la empresa, en cuanto llega, si nadie escribió otra.
  const fiscal = fiscalQuery.data;
  useEffect(() => {
    if (fiscal && getValues("igvRate") === "") setValue("igvRate", String(fiscal.igvRate));
  }, [fiscal, getValues, setValue]);

  const { fields, append, remove } = useFieldArray({ control, name: "lines" });
  const carrierId = useWatch({ control, name: "carrierId" });
  const currency = useWatch({ control, name: "currency" });
  const lines = useWatch({ control, name: "lines" });
  const documentType = useWatch({ control, name: "documentType" });
  const series = useWatch({ control, name: "series" });
  const totalAmount = useWatch({ control, name: "totalAmount" });
  const isNote = documentType !== "01";
  const requiresBreakdown = (currency ?? "").trim().toUpperCase() === (fiscal?.currency ?? "PEN");

  // Las facturas de este transportista que una nota puede corregir: no notas, no rechazadas.
  const referencedQuery = useQuery({
    queryKey: ["invoices-for-note", companyId, carrierId],
    enabled: isNote && Boolean(carrierId),
    queryFn: ({ signal }) => fetchInvoices(companyId, { carrierId, size: 100 }, signal),
  });
  const referenceable = (referencedQuery.data?.content ?? [])
    .filter((invoice) => (invoice.documentType ?? "01") === "01" && invoice.status !== "REJECTED");

  const total = Number(totalAmount);
  const taxPreview = fiscal && totalAmount?.trim() !== "" && Number.isFinite(total)
    ? previewTaxes(fiscal, documentType, currency ?? "", total)
    : null;

  /** Llena el IGV con la tasa y el total con subtotal + IGV. */
  function computeBreakdown() {
    const subtotal = Number(getValues("subtotalAmount"));
    const rate = Number(getValues("igvRate"));
    if (!Number.isFinite(subtotal) || !Number.isFinite(rate) || getValues("subtotalAmount").trim() === "") return;
    const igv = igvOf(subtotal, rate);
    setValue("igvAmount", igv.toFixed(2), { shouldDirty: true, shouldValidate: true });
    setValue("totalAmount", (subtotal + igv).toFixed(2), { shouldDirty: true, shouldValidate: true });
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
    const hasCpe = values.series.trim() !== "";
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
      documentType: values.documentType,
      ...(isNote ? { referencedInvoiceId: values.referencedInvoiceId } : {}),
      ...(hasCpe ? {
        series: values.series.trim().toUpperCase(),
        correlative: values.correlative.trim(),
        issuerRuc: values.issuerRuc.trim(),
      } : {}),
      ...(hasBreakdown ? {
        subtotalAmount: Number(values.subtotalAmount),
        igvRate: Number(values.igvRate),
        igvAmount: Number(values.igvAmount),
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

        <FormSection title={t("Cabecera")} help={t("Quién factura, con qué número y por cuánto. El total de la cabecera es lo que se compara.")}>
          <FormRow>
            <Controller
              control={control}
              name="documentType"
              render={({ field }) => (
                <TextField
                  select label={t("Tipo de documento")} size="small" fullWidth
                  value={field.value} onChange={(e) => field.onChange(e.target.value as DocumentTypeCode)}
                  helperText={isNote ? t("Una nota corrige una factura del mismo transportista; sus importes van en positivo.") : undefined}
                >
                  {DOCUMENT_TYPES.map((code) => (
                    <MenuItem key={code} value={code}>{enumLabel("documentType", code)}</MenuItem>
                  ))}
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
          <FormRow cols={3}>
            <TextField
              label={t("Serie")} size="small" fullWidth placeholder="F001"
              error={Boolean(errors.series)} helperText={errors.series?.message ?? t("CPE: serie, correlativo y RUC juntos.")}
              {...register("series", {
                validate: (value, all) => {
                  const any = [value, all.correlative, all.issuerRuc].some((part) => part.trim() !== "");
                  if (!any) return true;
                  if (value.trim() === "") return t("Serie, correlativo y RUC van juntos.");
                  return SERIES_PATTERN.test(value.trim().toUpperCase()) || t("F + 3 caracteres, E001 o 4 dígitos.");
                },
              })}
            />
            <TextField
              label={t("Correlativo")} size="small" fullWidth
              error={Boolean(errors.correlative)} helperText={errors.correlative?.message}
              {...register("correlative", {
                validate: (value, all) => {
                  if (all.series.trim() === "" && value.trim() === "") return true;
                  return (CORRELATIVE_PATTERN.test(value.trim()) && Number(value) > 0) || t("De 1 a 8 dígitos, no cero.");
                },
              })}
            />
            <TextField
              label={t("RUC emisor")} size="small" fullWidth
              error={Boolean(errors.issuerRuc)} helperText={errors.issuerRuc?.message}
              {...register("issuerRuc", {
                validate: (value, all) => {
                  if (all.series.trim() === "" && value.trim() === "") return true;
                  return isValidRuc(value.trim()) || t("RUC no válido: el dígito verificador no coincide.");
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
              label={t("Número de factura")} required={series.trim() === ""} size="small" fullWidth
              error={Boolean(errors.invoiceNumber)}
              helperText={errors.invoiceNumber?.message
                ?? (series.trim() !== "" ? t("Con serie y correlativo se compone solo.") : undefined)}
              {...register("invoiceNumber", {
                validate: (value, all) => all.series.trim() !== "" || value.trim() !== "" || t("Este campo es obligatorio"),
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
              label={t("Moneda")} required size="small" fullWidth placeholder="PEN"
              error={Boolean(errors.currency)} helperText={errors.currency?.message}
              {...register("currency", {
                required: t("Este campo es obligatorio"),
                pattern: { value: CURRENCY_PATTERN, message: t("Tres letras, p. ej. PEN") },
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
                  if (all.subtotalAmount.trim() === "" || all.igvAmount.trim() === "") return true;
                  return breakdownAddsUp(Number(all.subtotalAmount), Number(all.igvAmount), Number(value),
                    fiscal?.breakdownTolerance ?? 0.01)
                    || t("Subtotal + IGV no coincide con el total.");
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
          help={t("Subtotal sin IGV, tasa e IGV; obligatorio en PEN. La detracción o la retención las calcula el servidor con las reglas de la empresa.")}
          actions={
            <Button size="small" onClick={computeBreakdown}>{t("Calcular IGV y total")}</Button>
          }
        >
          <FormRow cols={3}>
            <TextField
              label={t("Subtotal (sin IGV)")} required={requiresBreakdown} size="small" fullWidth type="number"
              error={Boolean(errors.subtotalAmount)} helperText={errors.subtotalAmount?.message}
              {...register("subtotalAmount", {
                validate: (value, all) => {
                  const valid = numeric(value);
                  if (valid !== true) return valid;
                  const anyPart = [value, all.igvAmount].some((part) => part.trim() !== "");
                  if (requiresBreakdown && value.trim() === "") return t("En PEN el documento indica su desglose.");
                  return !anyPart || (value.trim() !== "" && all.igvAmount.trim() !== "")
                    || t("Subtotal, tasa e IGV van juntos.");
                },
              })}
            />
            <TextField
              label={t("Tasa IGV (%)")} size="small" fullWidth type="number"
              error={Boolean(errors.igvRate)} helperText={errors.igvRate?.message}
              {...register("igvRate", { validate: (value) => numeric(value) })}
            />
            <TextField
              label={t("IGV")} required={requiresBreakdown} size="small" fullWidth type="number"
              error={Boolean(errors.igvAmount)} helperText={errors.igvAmount?.message}
              {...register("igvAmount", {
                validate: (value) => {
                  const valid = numeric(value);
                  if (valid !== true) return valid;
                  return !requiresBreakdown || value.trim() !== "" || t("En PEN el documento indica su desglose.");
                },
              })}
            />
          </FormRow>
          {taxPreview && (
            <Alert severity="info" variant="outlined" sx={{ py: 0 }}>
              {taxPreview.kind === "detraction"
                ? t("Detracción {{code}} al {{rate}} %: {{amount}} PEN (se calcula, no se paga).", {
                  code: taxPreview.code, rate: fmtDecimal(taxPreview.rate, 2), amount: fmtDecimal(taxPreview.amount, 2),
                })
                : taxPreview.kind === "retention"
                  ? t("Retención al {{rate}} %: {{amount}} PEN.", {
                    rate: fmtDecimal(taxPreview.rate, 2), amount: fmtDecimal(taxPreview.amount, 2),
                  })
                  : taxPreview.reason === "below-threshold"
                    ? t("Sin detracción: el total no supera {{threshold}} PEN.", {
                      threshold: fmtDecimal(fiscal?.detractionThresholdAmount ?? 0, 2),
                    })
                    : taxPreview.reason === "note"
                      ? t("Una nota no calcula detracción propia.")
                      : taxPreview.reason === "currency"
                        ? t("Detracción y retención sólo se calculan en PEN.")
                        : t("La empresa no calcula detracción.")}
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
                      label={t("Importe de la línea (sin IGV)")} required size="small" fullWidth type="number"
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
