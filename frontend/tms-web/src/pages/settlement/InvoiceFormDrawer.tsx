import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useFieldArray, useForm, useWatch } from "react-hook-form";
import { Alert, Box, Button, IconButton, MenuItem, TextField, Tooltip, Typography } from "@mui/material";
import { AddRounded, CheckRounded, DeleteOutlineRounded, ReceiptLongRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import { fetchTrips } from "../../shared/api/planningApi";
import {
  CHARGE_CODES, receiveInvoice,
  type CarrierInvoiceRequest, type CarrierInvoiceView, type ChargeCode,
} from "../../shared/api/settlementApi";
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
}

const EMPTY_LINE: LineValues = {
  trip: null, chargeCode: "FREIGHT", description: "", quantity: "", unitAmount: "", lineAmount: "",
};

const KNOWN_FIELDS = new Set<string>([
  "carrierId", "invoiceNumber", "invoiceDate", "currency", "totalAmount", "externalReference", "notes",
]);

const toNumber = (value: string): number | null => (value.trim() === "" ? null : Number(value));

/** Un número válido o vacío. `negative` admite descuentos, que en una línea son legítimos. */
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
 */
export function InvoiceFormDrawer({ companyId, onClose, onSaved }: InvoiceFormDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-invoice-form", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "businessName,asc", signal }),
  });

  const {
    register, control, handleSubmit, setError,
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
    },
  });

  const { fields, append, remove } = useFieldArray({ control, name: "lines" });
  const carrierId = useWatch({ control, name: "carrierId" });
  const currency = useWatch({ control, name: "currency" });
  const lines = useWatch({ control, name: "lines" });

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
    const request: CarrierInvoiceRequest = {
      carrierId: values.carrierId,
      invoiceNumber: values.invoiceNumber.trim(),
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
      })),
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
              label={t("Número de factura")} required size="small" fullWidth
              error={Boolean(errors.invoiceNumber)} helperText={errors.invoiceNumber?.message}
              {...register("invoiceNumber", {
                required: t("Este campo es obligatorio"),
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
                validate: (value) => numeric(value),
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
                          helperText={field.value === "PENALTY" ? t("Las penalidades quedan fuera de la comparación.") : undefined}
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
                      label={t("Importe de la línea")} required size="small" fullWidth type="number"
                      error={Boolean(lineErrors?.lineAmount)} helperText={lineErrors?.lineAmount?.message}
                      {...register(`lines.${index}.lineAmount`, {
                        required: t("Este campo es obligatorio"),
                        validate: (value) => numeric(value, { negative: true }),
                      })}
                    />
                  </FormRow>
                  {duplicated && (
                    <Typography variant="caption" color="warning.main">
                      {t("Este viaje ya tiene otra línea con el mismo código de cargo: se tratará como duplicado.")}
                    </Typography>
                  )}
                </Box>
              );
            })}
            <Typography variant="caption" color="text.secondary" sx={{ fontVariantNumeric: "tabular-nums" }}>
              {t("Suma de las líneas: {{sum}} {{currency}}. Lo que se compara es el total de la cabecera.", {
                sum: fmtDecimal(linesSum, 2), currency: (currency ?? "").toUpperCase(),
              })}
            </Typography>
          </Box>
        </FormSection>
      </Box>
    </FormDrawer>
  );
}
