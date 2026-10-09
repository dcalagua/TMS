import { useState } from "react";
import { Alert, Box, Button, MenuItem, TextField } from "@mui/material";
import { CheckRounded, EventAvailableRounded } from "@mui/icons-material";
import { ContextCard, DateTimeInput, FormDrawer, StatusChip } from "../../shared/ui/components";
import {
  APPOINTMENT_PURPOSES, bookAppointment,
  type AppointmentPurpose, type LocationResourceView,
} from "../../shared/api/appointmentsApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

interface BookAppointmentDrawerProps {
  companyId: string;
  /** Las puertas activas del sitio. La ubicación viaja con la puerta, así que no se pasa aparte. */
  docks: LocationResourceView[];
  onClose: () => void;
  onBooked: () => void;
}

/**
 * Reservar una puerta (migración V41).
 *
 * <h2>Por qué la hora de fin es opcional</h2>
 * Cada puerta trae su propia duración por defecto. Obligar a calcular el fin en cada reserva
 * convierte el caso ordinario — una descarga de una hora — en un ejercicio de aritmética, y el
 * campo existe para las cargas que no son ordinarias.
 *
 * <h2>Por qué el conflicto se muestra literal</h2>
 * El backend responde nombrando la reserva que estorba, la hora a la que abre la puerta o el
 * motivo del cierre. Traducir eso a "no se pudo reservar" tiraría justo la parte que dice qué
 * hacer a continuación.
 */
export function BookAppointmentDrawer({
  companyId, docks, onClose, onBooked,
}: BookAppointmentDrawerProps) {
  const [resourceId, setResourceId] = useState(docks[0]?.id ?? "");
  const [purpose, setPurpose] = useState<AppointmentPurpose>("DELIVERY");
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [reference, setReference] = useState("");
  const [notes, setNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [touched, setTouched] = useState(false);

  const selectedDock = docks.find((dock) => dock.id === resourceId);
  const invalid = resourceId === "" || start === "";

  async function submit() {
    setSubmitting(true);
    try {
      await bookAppointment(companyId, {
        resourceId,
        purpose,
        windowStart: new Date(start).toISOString(),
        windowEnd: end ? new Date(end).toISOString() : null,
        reference: reference.trim() || null,
        notes: notes.trim() || null,
      });
      notifySuccess(t("Cita reservada"));
      onBooked();
    } catch (error) {
      notifyError(t("No se pudo reservar"), describeApiError(error as ApiError));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <FormDrawer
      open
      title={t("Reservar una puerta")}
      subtitle={t("Una puerta atiende un vehículo a la vez.")}
      icon={<EventAvailableRounded />}
      onClose={onClose}
      dirty={touched}
      size="sm"
      footer={
        <>
          <Button color="inherit" sx={{ color: "text.secondary" }} onClick={onClose} disabled={submitting}>{t("Cancelar")}</Button>
          <Button variant="contained" startIcon={<CheckRounded />} disabled={invalid || submitting} onClick={() => void submit()}>
            {submitting ? t("Reservando...") : t("Reservar cita")}
          </Button>
        </>
      }
    >
      <Box sx={{ display: "grid", gap: 2 }}>
        {docks.length === 0 ? (
          <Alert severity="warning" variant="outlined">
            {t("Este sitio no tiene puertas activas. Configúralas antes de reservar.")}
          </Alert>
        ) : (
          <>
            {selectedDock && (
              <ContextCard
                title={`${selectedDock.code} · ${selectedDock.name}`}
                status={<StatusChip label={enumLabel("resourceType", selectedDock.resourceType)} />}
                detail={selectedDock.openingHours.length === 0
                  ? t("Sin horario configurado: la puerta se considera abierta.")
                  : t("Abre: {{hours}}", {
                      hours: selectedDock.openingHours
                        .map((h) => `${enumLabel("dayOfWeek", h.day)} ${h.opensAt.slice(0, 5)}-${h.closesAt.slice(0, 5)}`)
                        .join(" · "),
                    })}
              />
            )}

            <TextField
              select size="small" label={t("Puerta")} value={resourceId} required
              onChange={(e) => { setResourceId(e.target.value); setTouched(true); }}
            >
              {docks.map((dock) => (
                <MenuItem key={dock.id} value={dock.id}>
                  {dock.code} · {dock.name} ({enumLabel("resourceType", dock.resourceType)})
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select size="small" label={t("Tipo")} value={purpose}
              onChange={(e) => { setPurpose(e.target.value as AppointmentPurpose); setTouched(true); }}
            >
              {APPOINTMENT_PURPOSES.map((value) => (
                <MenuItem key={value} value={value}>{enumLabel("appointmentPurpose", value)}</MenuItem>
              ))}
            </TextField>

            <DateTimeInput
              size="small" label={t("Inicio")} value={start} required
              onChange={(v) => { setStart(v); setTouched(true); }}
            />
            <DateTimeInput
              size="small" label={t("Fin (opcional)")} value={end}
              onChange={(v) => { setEnd(v); setTouched(true); }}
              helperText={selectedDock
                ? t("Vacío usa los {{m}} minutos por defecto de esta puerta.", { m: selectedDock.defaultSlotMinutes })
                : undefined}
            />

            <TextField
              size="small" label={t("Referencia")} value={reference}
              onChange={(e) => { setReference(e.target.value); setTouched(true); }}
              slotProps={{ htmlInput: { maxLength: 120 } }}
            />
            <TextField
              size="small" label={t("Notas")} value={notes} multiline minRows={2}
              onChange={(e) => { setNotes(e.target.value); setTouched(true); }}
              slotProps={{ htmlInput: { maxLength: 500 } }}
            />
          </>
        )}
      </Box>
    </FormDrawer>
  );
}
