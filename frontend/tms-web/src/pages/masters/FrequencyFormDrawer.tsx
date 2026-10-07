import { useState } from "react";
import { useForm } from "react-hook-form";
import {
  Alert, Box, Button, Checkbox, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, TextField, Typography,
} from "@mui/material";
import { CalendarViewWeekRounded, CheckRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import {
  createFrequency, updateFrequency, type FrequencyRequest, type FrequencyView,
} from "../../shared/api/frequenciesApi";
import {
  ActiveBadge, FormDateInput, FormDrawer, FormMeta, FormRow, FormSection, dataTableSx,
} from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";
import { DAY_NAMES } from "./FrequenciesPage";
import { FrequencyExceptionsPanel } from "./FrequencyExceptionsPanel";

const FORM_ID = "frequency-form";

/** Casa con la restricción de `code` del backend; vive junto al campo que valida. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;

interface WeeklyRuleFormValue {
  enabled: boolean;
  cutoffTime: string;
  leadTimeDays: string;
}

interface FrequencyFormValues {
  code: string;
  name: string;
  description: string;
  /** Longitud fija 7, índice 0 = lunes (dayOfWeek 1) … índice 6 = domingo (dayOfWeek 7). */
  weeklyRules: WeeklyRuleFormValue[];
}

interface FrequencyFormDrawerProps {
  companyId: string;
  /** `null` crea una frecuencia nueva; si no, el formulario edita esta. */
  frequency: FrequencyView | null;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_TOP_LEVEL_FIELDS = new Set<keyof FrequencyFormValues>(["code", "name", "description"]);

function buildDefaultWeeklyRules(frequency: FrequencyView | null): WeeklyRuleFormValue[] {
  return [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => {
    const existing = frequency?.weeklyRules.find((rule) => rule.dayOfWeek === dayOfWeek);
    return {
      enabled: existing?.enabled ?? false,
      cutoffTime: existing?.cutoffTime?.slice(0, 5) ?? "",
      leadTimeDays: existing?.leadTimeDays?.toString() ?? "",
    };
  });
}

/**
 * Crear y editar comparten un formulario. La rejilla semanal pinta siempre los siete días y
 * manda las siete filas al enviar: el backend reemplaza la cadencia entera
 * (`FrequencyService.replaceWeeklyRules`), no aplica un delta, así que mandar solo los días
 * marcados borraría en silencio la configuración de los demás.
 */
export function FrequencyFormDrawer({ companyId, frequency, onClose, onSaved }: FrequencyFormDrawerProps) {
  const isEdit = frequency !== null;
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<FrequencyFormValues>({
    defaultValues: {
      code: frequency?.code ?? "",
      name: frequency?.name ?? "",
      description: frequency?.description ?? "",
      weeklyRules: buildDefaultWeeklyRules(frequency),
    },
  });

  async function onSubmit(values: FrequencyFormValues) {
    setFormError(null);
    const request: FrequencyRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      description: values.description.trim() || null,
      weeklyRules: values.weeklyRules.map((rule, index) => ({
        dayOfWeek: index + 1,
        enabled: rule.enabled,
        cutoffTime: rule.cutoffTime.trim() === "" ? null : `${rule.cutoffTime}:00`,
        leadTimeDays: rule.leadTimeDays.trim() === "" ? null : Number(rule.leadTimeDays),
      })),
    };

    try {
      if (isEdit) await updateFrequency(companyId, frequency.id, request);
      else await createFrequency(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_TOP_LEVEL_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={<CalendarViewWeekRounded />}
      title={isEdit ? t("Editar frecuencia") : t("Nueva frecuencia")}
      subtitle={isEdit
        ? [frequency.code, frequency.name].filter(Boolean).join(" · ")
        : t("Cadencia semanal de servicio, con su corte y su anticipación por día.")}
      titleAdornment={isEdit ? <ActiveBadge active={frequency.active} /> : undefined}
      footerStart={isEdit ? <FormMeta>{t("Actualizado el {{date}}", { date: fmtDateTime(frequency.updatedAt) })}</FormMeta> : undefined}
      size="lg"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting} color="inherit" sx={{ color: "text.secondary" }}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar cambios") : t("Crear frecuencia")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 1 }}>{formError}</Alert>}

        <FormSection title={t("Identificación")} help={t("Cómo se reconoce la frecuencia al asignarla a destinos y rutas.")}>
          <FormRow template="180px minmax(0, 1fr)">
            <TextField
              label={t("Código")} required size="small" fullWidth
              error={Boolean(errors.code)} helperText={errors.code?.message}
              {...register("code", {
                required: t("Este campo es obligatorio"),
                maxLength: { value: 32, message: t("No puede superar los {{count}} caracteres", { count: 32 }) },
                pattern: { value: CODE_PATTERN, message: t("Solo letras, dígitos, guion bajo o guion") },
              })}
            />
            <TextField
              label={t("Nombre")} required size="small" fullWidth
              error={Boolean(errors.name)} helperText={errors.name?.message}
              {...register("name", {
                required: t("Este campo es obligatorio"),
                maxLength: { value: 200, message: t("No puede superar los {{count}} caracteres", { count: 200 }) },
              })}
            />
          </FormRow>
          <TextField
            label={t("Descripción")} size="small" fullWidth
            error={Boolean(errors.description)} helperText={errors.description?.message}
            {...register("description", {
              maxLength: { value: 1000, message: t("No puede superar los {{count}} caracteres", { count: 1000 }) },
            })}
          />
        </FormSection>

        <FormSection
          title={t("Cadencia semanal")}
          help={t("Qué días hay servicio, hasta qué hora se aceptan pedidos y con cuántos días de anticipación.")}
        >
        <TableContainer component={Paper} variant="outlined">
          <Table size="small" sx={dataTableSx}>
            <TableHead>
              <TableRow>
                <TableCell>{t("Día")}</TableCell>
                <TableCell>{t("Día de servicio")}</TableCell>
                <TableCell>{t("Hora de corte")}</TableCell>
                <TableCell>{t("Días de anticipación")}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {[0, 1, 2, 3, 4, 5, 6].map((index) => {
                const day = t(DAY_NAMES[index + 1]);
                return (
                  <TableRow key={index}>
                    <TableCell sx={{ fontWeight: 600 }}>{day}</TableCell>
                    <TableCell>
                      <Checkbox
                        size="small"
                        slotProps={{ input: { "aria-label": day } }}
                        {...register(`weeklyRules.${index}.enabled`)}
                      />
                    </TableCell>
                    <TableCell>
                      <FormDateInput
                        control={control} name={`weeklyRules.${index}.cutoffTime`} mode="time"
                        ariaLabel={t("Hora de corte de {{day}}", { day })}
                        size="small" sx={{ width: 130 }}
                      />
                    </TableCell>
                    <TableCell>
                      <TextField
                        type="number" size="small" sx={{ width: 110 }}
                        aria-label={t("Días de anticipación de {{day}}", { day })}
                        error={Boolean(errors.weeklyRules?.[index]?.leadTimeDays)}
                        {...register(`weeklyRules.${index}.leadTimeDays`, {
                          min: { value: 0, message: t("Debe ser cero o mayor") },
                        })}
                      />
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
        </FormSection>
      </Box>

      {/* Las excepciones viven fuera del <form>: se guardan por su cuenta contra la frecuencia
          ya existente, y anidar un formulario dentro de otro no es válido. */}
      <Box sx={{ borderTop: "1px solid", borderColor: "divider" }}>
        <FormSection
          title={t("Excepciones")}
          help={t("Fechas concretas en las que la cadencia semanal no aplica. Se guardan al momento.")}
        >
          {frequency ? (
            <FrequencyExceptionsPanel companyId={companyId} frequencyId={frequency.id} canManage />
          ) : (
            // Un sub-recurso necesita algo de lo que colgar. Decirlo es mejor que un panel que
            // parece roto porque cada llamada suya daría 404.
            <Typography variant="body2" color="text.secondary">
              {t("Guarda la frecuencia primero para poder añadirle excepciones.")}
            </Typography>
          )}
        </FormSection>
      </Box>
    </FormDrawer>
  );
}
