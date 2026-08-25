import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { ViewKanbanRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchOrigins } from "../../shared/api/originsApi";
import { createPlanningRun, type PlanningRunDetailView } from "../../shared/api/planningApi";
import { FormDrawer } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { today } from "../../lib/locale";

const FORM_ID = "planning-run-form";

interface PlanningRunFormValues {
  originId: string;
  planningDate: string;
  notes: string;
}

interface PlanningRunFormDrawerProps {
  companyId: string;
  onClose: () => void;
  onCreated: (detail: PlanningRunDetailView) => void;
}

const KNOWN_FIELDS = new Set<keyof PlanningRunFormValues>(["originId", "planningDate", "notes"]);

/**
 * Abrir un plan: un origen y un día, y nada más.
 *
 * No hay selector de modo. V1 solo crea planes manuales; la planificación automática es una
 * acción *dentro* de un plan ya abierto, no otra clase de plan — así el planificador siempre
 * puede revisar y corregir lo que propuso el motor antes de confirmar.
 */
export function PlanningRunFormDrawer({ companyId, onClose, onCreated }: PlanningRunFormDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const originsQuery = useQuery({
    queryKey: ["origins-for-planning-run-form", companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<PlanningRunFormValues>({
    defaultValues: { originId: "", planningDate: today(), notes: "" },
  });

  async function onSubmit(values: PlanningRunFormValues) {
    setFormError(null);
    try {
      const detail = await createPlanningRun(companyId, {
        originId: values.originId,
        planningDate: values.planningDate,
        notes: values.notes.trim() || null,
      });
      onCreated(detail);
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={<ViewKanbanRounded />}
      title={t("Nuevo plan")}
      subtitle={t("Un plan agrupa los viajes de un origen para un día concreto.")}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : t("Abrir plan")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        <Box sx={{ display: "grid", gap: 2, mb: 2 }}>
          <Controller
            control={control}
            name="originId"
            rules={{ required: t("Este campo es obligatorio") }}
            render={({ field }) => (
              <TextField
                select label={t("Origen")} required size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value)}
                error={Boolean(errors.originId)} helperText={errors.originId?.message}
              >
                <MenuItem value="">{t("Selecciona un origen")}</MenuItem>
                {(originsQuery.data?.content ?? []).map((origin) => (
                  <MenuItem key={origin.id} value={origin.id}>{origin.code} · {origin.name}</MenuItem>
                ))}
              </TextField>
            )}
          />
          <TextField
            label={t("Fecha de planificación")} required size="small" fullWidth type="date"
            slotProps={{ inputLabel: { shrink: true } }}
            error={Boolean(errors.planningDate)} helperText={errors.planningDate?.message}
            {...register("planningDate", { required: t("Este campo es obligatorio") })}
          />
          <TextField
            label={t("Notas")} size="small" fullWidth multiline rows={3}
            {...register("notes")}
          />
        </Box>

        <Typography variant="caption" color="text.secondary">
          {t("Solo entran en el plan los pedidos liberados para planificación con ese origen y esa fecha.")}
        </Typography>
      </Box>
    </FormDrawer>
  );
}
