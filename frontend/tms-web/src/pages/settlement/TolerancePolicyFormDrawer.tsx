import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField } from "@mui/material";
import { CheckRounded, TuneRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import {
  createTolerancePolicy, updateTolerancePolicy, type TolerancePolicyView,
} from "../../shared/api/settlementApi";
import { ActiveBadge, FormDrawer, FormMeta, FormRow, FormSection } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";
import { validateTolerance, type ToleranceFieldName, type TolerancePolicyFormValues } from "./toleranceRules";

const FORM_ID = "tolerance-policy-form";
const KNOWN_FIELDS = new Set<string>(["carrierId", "absoluteAmount", "percentage", "currency"]);

const toNumber = (value: string): number | null => (value.trim() === "" ? null : Number(value));

interface TolerancePolicyFormDrawerProps {
  companyId: string;
  /** Null para crear. Al editar, el ámbito no cambia: sólo los límites. */
  policy: TolerancePolicyView | null;
  onClose: () => void;
  onSaved: () => void;
}

/**
 * Crear o editar una política de tolerancia de conciliación.
 *
 * El ámbito - toda la empresa o un transportista - se decide al crearla y no se mueve después:
 * cambiarlo sería otra política, y el servidor tiene una sola activa por ámbito.
 */
export function TolerancePolicyFormDrawer({ companyId, policy, onClose, onSaved }: TolerancePolicyFormDrawerProps) {
  const isEdit = policy !== null;
  const [formError, setFormError] = useState<string | null>(null);

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-tolerance-form", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "businessName,asc", signal }),
    enabled: !isEdit,
  });

  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<TolerancePolicyFormValues>({
    defaultValues: {
      scope: policy?.carrierId ? "CARRIER" : "COMPANY",
      carrierId: policy?.carrierId ?? "",
      absoluteAmount: policy?.absoluteAmount?.toString() ?? "",
      percentage: policy?.percentage?.toString() ?? "",
      currency: policy?.currency ?? (isEdit ? "" : "PEN"),
    },
  });

  const scope = useWatch({ control, name: "scope" });

  async function onSubmit(values: TolerancePolicyFormValues) {
    setFormError(null);
    const verdict = validateTolerance(values);
    for (const [field, message] of Object.entries(verdict.fields)) {
      setError(field as ToleranceFieldName, { message });
    }
    if (verdict.form !== null || Object.keys(verdict.fields).length > 0) {
      setFormError(verdict.form);
      return;
    }

    const bounds = {
      absoluteAmount: toNumber(values.absoluteAmount),
      percentage: toNumber(values.percentage),
      currency: values.currency.trim() === "" ? null : values.currency.trim().toUpperCase(),
    };
    try {
      if (isEdit) await updateTolerancePolicy(companyId, policy.id, bounds);
      else await createTolerancePolicy(companyId, { ...bounds, carrierId: values.scope === "CARRIER" ? values.carrierId : null });
      onSaved();
    } catch (error) {
      const apiError = error as ApiError;
      // El 409 nombra el ámbito que ya tiene política activa: esa frase es la que dice qué hacer.
      if (apiError.status === 409 && apiError.problem?.detail) setFormError(apiError.problem.detail);
      else setFormError(applyApiFieldErrors(apiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={<TuneRounded />}
      title={isEdit ? t("Editar tolerancia") : t("Nueva tolerancia")}
      subtitle={isEdit
        ? (policy.carrierName ?? (policy.carrierId ? t("Transportista") : t("Toda la empresa")))
        : t("Cuánta diferencia se acepta sin abrir una discrepancia.")}
      titleAdornment={isEdit ? <ActiveBadge active={policy.active} /> : undefined}
      footerStart={isEdit ? <FormMeta>{t("Actualizado el {{date}}", { date: fmtDateTime(policy.updatedAt) })}</FormMeta> : undefined}
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting} color="inherit" sx={{ color: "text.secondary" }}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar cambios") : t("Crear tolerancia")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 1 }}>{formError}</Alert>}

        <FormSection
          title={t("Ámbito")}
          help={t("La de un transportista desplaza a la de toda la empresa para sus facturas. Sólo hay una activa por ámbito.")}
        >
          <FormRow>
            <Controller
              control={control}
              name="scope"
              render={({ field }) => (
                <TextField
                  select label={t("Aplica a")} size="small" fullWidth disabled={isEdit}
                  value={field.value} onChange={(e) => field.onChange(e.target.value)}
                >
                  <MenuItem value="COMPANY">{t("Toda la empresa")}</MenuItem>
                  <MenuItem value="CARRIER">{t("Un transportista")}</MenuItem>
                </TextField>
              )}
            />
            {scope === "CARRIER" && (
              isEdit ? (
                <TextField label={t("Transportista")} size="small" fullWidth disabled value={policy.carrierName ?? "—"} />
              ) : (
                <Controller
                  control={control}
                  name="carrierId"
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
              )
            )}
          </FormRow>
        </FormSection>

        <FormSection
          title={t("Límites")}
          help={t("Una diferencia se acepta si cabe en el importe absoluto o en el porcentaje. Al menos uno de los dos.")}
        >
          <FormRow cols={3}>
            <TextField
              label={t("Importe absoluto")} size="small" fullWidth type="number"
              error={Boolean(errors.absoluteAmount)} helperText={errors.absoluteAmount?.message}
              {...register("absoluteAmount")}
            />
            <TextField
              label={t("Moneda")} size="small" fullWidth placeholder="PEN"
              error={Boolean(errors.currency)}
              helperText={errors.currency?.message ?? t("Obligatoria con un importe absoluto.")}
              {...register("currency")}
            />
            <TextField
              label={t("Porcentaje")} size="small" fullWidth type="number"
              error={Boolean(errors.percentage)} helperText={errors.percentage?.message ?? t("De 0 a 100.")}
              {...register("percentage")}
            />
          </FormRow>
        </FormSection>
      </Box>
    </FormDrawer>
  );
}
