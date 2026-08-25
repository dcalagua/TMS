import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { ReportProblemRounded } from "@mui/icons-material";
import {
  STOP_SCOPED_EXCEPTION_TYPES, TRIP_EXCEPTION_TYPES,
  type TripExceptionType, type TripStopView,
} from "../../shared/api/planningApi";
import { FormDrawer } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

const FORM_ID = "trip-problem-form";

export interface TripProblemValues {
  exceptionType: TripExceptionType;
  notes: string | null;
  /** Solo en modo `report`: a qué parada se ata la incidencia, o null para el viaje entero. */
  tripStopId: string | null;
}

export type TripProblemMode = "skip" | "fail" | "report";

interface TripProblemDrawerProps {
  mode: TripProblemMode;
  /** Las paradas del viaje, para el selector del modo `report`. */
  stops: TripStopView[];
  /** La parada afectada en los modos `skip` y `fail`. */
  stopLabel?: string;
  onClose: () => void;
  /** Lanza un `Error` con la frase del servidor si el backend rechaza: el drawer se queda
   * abierto con el mensaje dentro, en vez de cerrarse y dejar un toast detrás de nada. */
  onSubmit: (values: TripProblemValues) => Promise<void>;
}

const COPY: Record<TripProblemMode, { title: string; subtitle: string; confirm: string }> = {
  skip: {
    title: "Saltar la parada",
    subtitle: "La parada no se va a servir. Di por qué: eso abre una incidencia del viaje.",
    confirm: "Saltar parada",
  },
  fail: {
    title: "Marcar la parada como fallida",
    subtitle: "Se intentó y no se pudo servir. El motivo abre una incidencia del viaje.",
    confirm: "Marcar fallida",
  },
  report: {
    title: "Reportar una incidencia",
    subtitle: "Algo que pasó en el viaje y hay que dejar registrado.",
    confirm: "Reportar",
  },
};

/**
 * Saltar una parada, marcarla fallida y reportar una incidencia del viaje comparten formulario.
 *
 * Los tres necesitan lo mismo —un tipo de motivo y una frase— y a qué endpoint van lo decide el
 * modo. Tenerlos en tres drawers casi idénticos es como el tercero acaba pidiendo un motivo que
 * los otros dos no ofrecen.
 *
 * En los modos de parada la lista de tipos se acota a los que tienen sentido en una parada: el
 * backend rechaza los demás, y ofrecer un motivo que va a fallar es ofrecer un error.
 */
export function TripProblemDrawer({ mode, stops, stopLabel, onClose, onSubmit }: TripProblemDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);
  const copy = COPY[mode];
  const types = mode === "report" ? TRIP_EXCEPTION_TYPES : STOP_SCOPED_EXCEPTION_TYPES;

  const {
    register, control, handleSubmit,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<{ exceptionType: TripExceptionType | ""; notes: string; tripStopId: string }>({
    defaultValues: { exceptionType: "", notes: "", tripStopId: "" },
  });

  async function submit(values: { exceptionType: TripExceptionType | ""; notes: string; tripStopId: string }) {
    setFormError(null);
    if (values.exceptionType === "") {
      setFormError(t("Elige un motivo."));
      return;
    }
    try {
      await onSubmit({
        exceptionType: values.exceptionType,
        notes: values.notes.trim() || null,
        tripStopId: values.tripStopId || null,
      });
    } catch (error) {
      setFormError((error as Error).message);
    }
  }

  return (
    <FormDrawer
      open
      icon={<ReportProblemRounded />}
      title={t(copy.title)}
      subtitle={stopLabel ?? t(copy.subtitle)}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" color="error" disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : t(copy.confirm)}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(submit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        {stopLabel && (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t(copy.subtitle)}
          </Typography>
        )}

        <Box sx={{ display: "grid", gap: 2 }}>
          {mode === "report" && (
            <Controller
              control={control}
              name="tripStopId"
              render={({ field }) => (
                <TextField
                  select label={t("Parada")} size="small" fullWidth
                  value={field.value} onChange={(e) => field.onChange(e.target.value)}
                  helperText={t("Déjalo vacío si la incidencia es del viaje entero.")}
                >
                  <MenuItem value="">{t("Todo el viaje")}</MenuItem>
                  {stops.map((stop) => (
                    <MenuItem key={stop.id} value={stop.id}>
                      {stop.sequence}. {stop.destinationName ?? stop.destinationCode ?? stop.destinationId}
                    </MenuItem>
                  ))}
                </TextField>
              )}
            />
          )}

          <Controller
            control={control}
            name="exceptionType"
            rules={{ required: t("Este campo es obligatorio") }}
            render={({ field }) => (
              <TextField
                select label={t("Motivo")} required size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value as TripExceptionType)}
                error={Boolean(errors.exceptionType)} helperText={errors.exceptionType?.message}
              >
                <MenuItem value="">{t("Selecciona un motivo")}</MenuItem>
                {types.map((type) => (
                  <MenuItem key={type} value={type}>{enumLabel("tripExceptionType", type)}</MenuItem>
                ))}
              </TextField>
            )}
          />

          <TextField
            label={t("Notas")} size="small" fullWidth multiline rows={3}
            {...register("notes", {
              maxLength: { value: 1000, message: t("No puede superar los {{count}} caracteres", { count: 1000 }) },
            })}
            error={Boolean(errors.notes)} helperText={errors.notes?.message}
          />
        </Box>
      </Box>
    </FormDrawer>
  );
}
