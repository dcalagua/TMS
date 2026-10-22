import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Controller, useFieldArray, useForm, useWatch } from "react-hook-form";
import { Alert, Box, Button, IconButton, MenuItem, TextField, Tooltip, Typography } from "@mui/material";
import { AddRounded, CheckRounded, DeleteOutlineRounded, GavelRounded } from "@mui/icons-material";
import {
  CLAIM_TYPES, fetchClaimDraft, openClaim, updateClaim,
  type ClaimDetail, type ClaimDraft, type ClaimType, type OpenClaimInput,
} from "../../shared/api/claimsApi";
import { fetchTrips } from "../../shared/api/planningApi";
import { fetchFiscalSettings } from "../../shared/api/settlementApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  FormDateInput, FormDrawer, FormRow, FormSection, LookupField, type LookupOption,
} from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDecimal, today } from "../../lib/locale";
import { draftDescription } from "./claimUi";

const FORM_ID = "freight-claim-form";
const CURRENCY_PATTERN = /^[A-Za-z]{3}$/;

/** De dónde nace un reclamo nuevo: un viaje, una de sus entregas o una de sus incidencias. */
export interface ClaimSource {
  tripId?: string;
  orderDeliveryId?: string;
  tripExceptionId?: string;
}

export type ClaimFormMode = { kind: "open"; source: ClaimSource | null } | { kind: "edit"; claim: ClaimDetail };

interface LineValues {
  orderLineId: string;
  description: string;
  quantity: string;
  uom: string;
  amount: string;
}

interface ClaimFormValues {
  claimType: ClaimType | "";
  incidentDate: string;
  description: string;
  claimedAmount: string;
  currency: string;
  lines: LineValues[];
}

const EMPTY_LINE: LineValues = { orderLineId: "", description: "", quantity: "", uom: "", amount: "" };

const toNumber = (value: string): number | null => (value.trim() === "" ? null : Number(value));

function positive(value: string): true | string {
  if (value.trim() === "") return true;
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return t("Introduce un número válido");
  if (parsed <= 0) return t("Debe ser mayor que cero");
  return true;
}

function nonNegative(value: string): true | string {
  if (value.trim() === "") return t("Este campo es obligatorio");
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return t("Introduce un número válido");
  if (parsed < 0) return t("No puede ser negativo");
  return true;
}

/** Los valores del formulario desde un borrador: las líneas rechazadas, sin importe (TMS no sabe cuánto vale una silla). */
function fromDraft(draft: ClaimDraft, currency: string): ClaimFormValues {
  return {
    claimType: draft.suggestedType ?? "",
    incidentDate: draft.incidentDate ?? today(),
    description: draftDescription(draft),
    claimedAmount: "",
    currency,
    lines: draft.refusedLines.map((line) => ({
      orderLineId: line.orderLineId,
      description: [line.materialCode, line.orderLineNumber !== null ? t("línea {{n}}", { n: line.orderLineNumber }) : null]
        .filter(Boolean).join(" · ") || t("Línea del pedido"),
      quantity: String(line.quantity),
      uom: line.uom,
      amount: "",
    })),
  };
}

function fromClaim(claim: ClaimDetail): ClaimFormValues {
  return {
    claimType: claim.summary.claimType,
    incidentDate: claim.summary.incidentDate,
    description: claim.description,
    claimedAmount: claim.lines.length === 0 ? String(claim.summary.claimedAmount) : "",
    currency: claim.summary.currency,
    lines: claim.lines.map((line) => ({
      orderLineId: line.orderLineId ?? "",
      description: line.description,
      quantity: line.quantity === null ? "" : String(line.quantity),
      uom: line.uom ?? "",
      amount: String(line.amount),
    })),
  };
}

interface ClaimFormDrawerProps {
  companyId: string;
  mode: ClaimFormMode;
  onClose: () => void;
  onSaved: (claim: ClaimDetail) => void;
}

/**
 * Abrir o corregir un reclamo de carga (V71).
 *
 * Desde una entrega con faltante o una incidencia, el servidor devuelve un borrador con el viaje, el
 * transportista, el pedido y las líneas rechazadas; la pantalla lo vuelca en el formulario y la
 * persona completa lo que TMS no sabe: cuánto se reclama. El transportista no se elige: es el del
 * viaje. Con líneas, el importe reclamado es su suma.
 */
export function ClaimFormDrawer({ companyId, mode, onClose, onSaved }: ClaimFormDrawerProps) {
  const isEdit = mode.kind === "edit";
  const [formError, setFormError] = useState<string | null>(null);
  const [trip, setTrip] = useState<LookupOption | null>(null);

  const source: ClaimSource | null = mode.kind === "open"
    ? (mode.source ?? (trip ? { tripId: trip.id } : null))
    : null;

  const draftQuery = useQuery({
    queryKey: ["claim-draft", companyId, source],
    queryFn: ({ signal }) => fetchClaimDraft(companyId, source ?? {}, signal),
    enabled: companyId !== "" && source !== null,
  });
  const regimeQuery = useQuery({
    queryKey: ["fiscal-settings", companyId],
    queryFn: ({ signal }) => fetchFiscalSettings(companyId, signal),
    enabled: companyId !== "" && !isEdit,
  });

  const {
    register, control, handleSubmit, reset, getValues,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ClaimFormValues>({
    defaultValues: isEdit ? fromClaim(mode.claim) : {
      claimType: "", incidentDate: today(), description: "", claimedAmount: "", currency: "", lines: [],
    },
  });
  const { fields, append, remove } = useFieldArray({ control, name: "lines" });
  const lines = useWatch({ control, name: "lines" });
  const currency = useWatch({ control, name: "currency" });

  // El borrador, en cuanto llega, se vuelca en el formulario. La moneda, la del régimen fiscal.
  const draft = draftQuery.data;
  const fiscalCurrency = regimeQuery.data?.fiscalCurrency ?? "";
  useEffect(() => {
    if (isEdit || !draft) return;
    reset(fromDraft(draft, getValues("currency") || fiscalCurrency));
  }, [draft, isEdit, reset, getValues, fiscalCurrency]);
  useEffect(() => {
    if (isEdit || fiscalCurrency === "" || getValues("currency") !== "") return;
    reset({ ...getValues(), currency: fiscalCurrency }, { keepDirty: true });
  }, [fiscalCurrency, isEdit, reset, getValues]);

  const linesSum = (lines ?? []).reduce((sum, line) => {
    const value = Number(line?.amount);
    return Number.isFinite(value) ? sum + value : sum;
  }, 0);
  const hasLines = (lines ?? []).length > 0;
  const claimable = isEdit || (draft?.claimable ?? false);

  async function searchTrips(term: string, signal: AbortSignal): Promise<LookupOption[]> {
    const page = await fetchTrips({ companyId, size: 20, signal, shipmentNumber: term || undefined });
    return page.content.map((row) => ({
      id: row.id,
      code: row.shipmentNumber,
      name: [fmtDate(row.planningDate), row.carrierName].filter(Boolean).join(" · "),
    }));
  }

  async function onSubmit(values: ClaimFormValues) {
    setFormError(null);
    if (values.claimType === "") {
      setFormError(t("Elige para qué es el reclamo."));
      return;
    }
    const amount = hasLines ? null : toNumber(values.claimedAmount);
    if (!hasLines && (amount === null || amount <= 0)) {
      setFormError(t("Indica el importe reclamado, o las líneas de las que sale."));
      return;
    }
    if (hasLines && linesSum <= 0) {
      setFormError(t("Las líneas tienen que sumar más que cero."));
      return;
    }
    const body = {
      claimType: values.claimType,
      incidentDate: values.incidentDate,
      description: values.description.trim(),
      claimedAmount: amount,
      currency: values.currency.trim().toUpperCase(),
      lines: values.lines.map((line) => ({
        orderLineId: line.orderLineId || null,
        description: line.description.trim(),
        quantity: toNumber(line.quantity),
        uom: line.uom.trim() || null,
        amount: Number(line.amount),
      })),
    };
    try {
      if (isEdit) {
        onSaved(await updateClaim(companyId, mode.claim.summary.id, body));
      } else if (draft) {
        const input: OpenClaimInput = {
          ...body,
          tripId: draft.tripId,
          orderId: draft.orderId,
          orderDeliveryId: draft.orderDeliveryId,
          tripExceptionId: draft.tripExceptionId,
        };
        onSaved(await openClaim(companyId, input));
      }
    } catch (error) {
      setFormError(describeApiError(error as ApiError));
    }
  }

  const subject = isEdit
    ? `${mode.claim.summary.claimNumber} · ${mode.claim.summary.shipmentNumber ?? ""}`
    : draft ? [draft.shipmentNumber, draft.carrierName, draft.orderNumber].filter(Boolean).join(" · ") : undefined;

  return (
    <FormDrawer
      open
      icon={<GavelRounded />}
      title={isEdit ? t("Corregir reclamo") : t("Abrir reclamo")}
      subtitle={subject ?? t("Contra el transportista del viaje, por daño, faltante, pérdida o demora.")}
      size="lg"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting} color="inherit" sx={{ color: "text.secondary" }}>{t("Cancelar")}</Button>
          <Button
            type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />}
            disabled={isSubmitting || !claimable || (!isEdit && !draft)}
          >
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar cambios") : t("Abrir reclamo")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 1 }}>{formError}</Alert>}
        {draftQuery.isError && (
          <Alert severity="error" sx={{ mb: 1 }}>{describeApiError(draftQuery.error as ApiError)}</Alert>
        )}

        {mode.kind === "open" && mode.source === null && (
          <FormSection title={t("Viaje")} help={t("El reclamo es contra el transportista que lo ejecutó.")}>
            <LookupField
              label={t("Envío")} value={trip?.id ?? ""} selected={trip} onChange={setTrip}
              search={searchTrips} queryKey={["claim-trip-lookup", companyId]} required fullWidth size="small"
            />
          </FormSection>
        )}

        {draft && !draft.claimable && (
          <Alert severity="warning" sx={{ mb: 1 }}>{draft.notClaimableReason}</Alert>
        )}
        {draft && draft.existingClaims.length > 0 && (
          <Alert severity="info" variant="outlined" sx={{ mb: 1 }}>
            {t("Este viaje ya tiene reclamos: {{list}}. Revisa que no estés reclamando lo mismo dos veces.", {
              list: draft.existingClaims.map((claim) => `${claim.claimNumber} (${enumLabel("claimStatus", claim.status)})`).join(", "),
            })}
          </Alert>
        )}

        {(isEdit || draft) && (
          <>
            <FormSection title={t("Reclamo")} help={t("Qué pasó, cuándo y cuánto se le pide al transportista.")}>
              <FormRow cols={3}>
                <Controller
                  control={control} name="claimType"
                  rules={{ required: t("Este campo es obligatorio") }}
                  render={({ field }) => (
                    <TextField
                      select label={t("Tipo")} required size="small" fullWidth value={field.value}
                      onChange={(e) => field.onChange(e.target.value)}
                      error={Boolean(errors.claimType)} helperText={errors.claimType?.message}
                    >
                      {CLAIM_TYPES.map((type) => (
                        <MenuItem key={type} value={type}>{enumLabel("claimType", type)}</MenuItem>
                      ))}
                    </TextField>
                  )}
                />
                <FormDateInput
                  control={control} name="incidentDate" mode="date"
                  rules={{ required: t("Este campo es obligatorio") }}
                  label={t("Fecha del incidente")} required size="small" fullWidth
                  error={Boolean(errors.incidentDate)} helperText={errors.incidentDate?.message}
                />
                <TextField
                  label={t("Moneda")} required size="small" fullWidth placeholder="USD"
                  error={Boolean(errors.currency)} helperText={errors.currency?.message}
                  {...register("currency", {
                    validate: (value) => CURRENCY_PATTERN.test(value.trim()) || t("Código ISO de tres letras (USD, PEN)."),
                  })}
                />
              </FormRow>
              <TextField
                label={t("Descripción")} required size="small" fullWidth multiline minRows={3}
                error={Boolean(errors.description)} helperText={errors.description?.message}
                {...register("description", {
                  validate: (value) => value.trim() !== "" || t("Describe qué pasó."),
                  maxLength: { value: 2000, message: t("No puede superar los {{count}} caracteres", { count: 2000 }) },
                })}
              />
              {!hasLines && (
                <FormRow cols={3}>
                  <TextField
                    label={t("Importe reclamado")} required size="small" fullWidth type="number"
                    error={Boolean(errors.claimedAmount)} helperText={errors.claimedAmount?.message}
                    {...register("claimedAmount", { validate: positive })}
                  />
                </FormRow>
              )}
            </FormSection>

            <FormSection
              title={t("Líneas")}
              help={t("Opcional. Con líneas, el importe reclamado es su suma. Las rechazadas en la entrega llegan precargadas, sin importe.")}
              actions={
                <Button size="small" startIcon={<AddRounded />} onClick={() => append({ ...EMPTY_LINE })}>
                  {t("Añadir línea")}
                </Button>
              }
            >
              <Box sx={{ display: "grid", gap: 1.5 }}>
                {fields.map((item, index) => {
                  const lineErrors = errors.lines?.[index];
                  return (
                    <Box key={item.id} sx={{ display: "flex", gap: 1, alignItems: "flex-start", flexWrap: { xs: "wrap", md: "nowrap" } }}>
                      <TextField
                        label={t("Concepto")} required size="small" sx={{ flex: 3, minWidth: 180 }}
                        error={Boolean(lineErrors?.description)} helperText={lineErrors?.description?.message}
                        {...register(`lines.${index}.description`, {
                          validate: (value) => value.trim() !== "" || t("Este campo es obligatorio"),
                          maxLength: { value: 500, message: t("No puede superar los {{count}} caracteres", { count: 500 }) },
                        })}
                      />
                      <TextField
                        label={t("Cantidad")} size="small" type="number" sx={{ flex: 1, minWidth: 90 }}
                        error={Boolean(lineErrors?.quantity)} helperText={lineErrors?.quantity?.message}
                        {...register(`lines.${index}.quantity`, { validate: positive })}
                      />
                      <TextField label={t("Unidad")} size="small" sx={{ flex: 1, minWidth: 70 }} {...register(`lines.${index}.uom`)} />
                      <TextField
                        label={t("Importe")} required size="small" type="number" sx={{ flex: 1, minWidth: 100 }}
                        error={Boolean(lineErrors?.amount)} helperText={lineErrors?.amount?.message}
                        {...register(`lines.${index}.amount`, { validate: nonNegative })}
                      />
                      <Tooltip title={t("Quitar línea")}>
                        <IconButton aria-label={t("Quitar línea")} onClick={() => remove(index)} sx={{ mt: 0.25 }}>
                          <DeleteOutlineRounded fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    </Box>
                  );
                })}
                {hasLines && (
                  <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
                    {t("Importe reclamado: {{sum}} {{currency}}", {
                      sum: fmtDecimal(linesSum, 2), currency: (currency ?? "").toUpperCase(),
                    })}
                  </Typography>
                )}
              </Box>
            </FormSection>
          </>
        )}
      </Box>
    </FormDrawer>
  );
}
