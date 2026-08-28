import { useMemo, useState } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert, Box, Button, Chip, MenuItem, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, TextField, Tooltip, Typography,
} from "@mui/material";
import {
  EventAvailableRounded, CheckCircleRounded, CancelRounded, LocalShippingRounded,
  DoneAllRounded, PersonOffRounded, ScheduleRounded,
} from "@mui/icons-material";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  arriveAppointment, cancelAppointment, completeAppointment, confirmAppointment,
  fetchAppointments, fetchLocationResources, markAppointmentNoShow, rescheduleAppointment,
  type AppointmentStatus, type AppointmentView,
} from "../../shared/api/appointmentsApi";
import { fetchDestinations } from "../../shared/api/destinationsApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  ActionMenu, EmptyState, ErrorState, LoadingState, PageHeader, StatusChip, dataTableSx,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { confirmDialog, notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import type { StatusTone } from "../../theme";
import { fmtDateTime } from "../../lib/locale";
import { t } from "../../lib/i18n";
import { BookAppointmentDrawer } from "./BookAppointmentDrawer";

/**
 * El tablero de muelles (migración V41).
 *
 * <h2>Por qué un día y un sitio</h2>
 * Un muelle es una cola, y una cola se lee por día. "Todas las citas" ordenadas por fecha sería una
 * lista que nadie usa: quien está en la garita quiere ver qué llega hoy a *esta* nave.
 *
 * <h2>Por qué se dice la zona horaria</h2>
 * Las ventanas son instantes absolutos y se muestran en la zona del navegador. En una operación de
 * un solo país eso coincide con la del sitio; en una de varios, no — y una hora sin decir de dónde
 * es, es exactamente cómo un camión llega con cinco horas de diferencia.
 */
export function AppointmentsPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("appointments.appointment:manage");

  const [locationId, setLocationId] = useState("");
  const [day, setDay] = useState(() => new Date().toISOString().slice(0, 10));
  const [booking, setBooking] = useState(false);
  const queryClient = useQueryClient();

  const locationsQuery = useQuery({
    queryKey: ["locations-for-appointments", companyId],
    queryFn: ({ signal }) =>
      fetchDestinations({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: Boolean(companyId),
  });

  // El día del navegador, de medianoche a medianoche. El backend compara instantes, así que se
  // envían con el desplazamiento local en lugar de una fecha suelta.
  const range = useMemo(() => ({
    from: new Date(`${day}T00:00:00`).toISOString(),
    to: new Date(`${day}T23:59:59`).toISOString(),
  }), [day]);

  const resourcesQuery = useQuery({
    queryKey: ["dock-resources", companyId, locationId],
    queryFn: ({ signal }) => fetchLocationResources(companyId, locationId, signal),
    enabled: Boolean(companyId && locationId),
  });

  const appointmentsQuery = useQuery({
    queryKey: ["appointments", companyId, locationId, range.from, range.to],
    queryFn: ({ signal }) => fetchAppointments(companyId, locationId, range.from, range.to, signal),
    enabled: Boolean(companyId && locationId),
    placeholderData: keepPreviousData,
  });

  const refresh = () =>
    void queryClient.invalidateQueries({ queryKey: ["appointments", companyId, locationId] });

  const act = async (action: () => Promise<AppointmentView>, success: string, failure: string) => {
    try {
      await action();
      notifySuccess(t(success));
      refresh();
    } catch (error) {
      notifyError(t(failure), describeApiError(error as ApiError));
    }
  };

  async function cancel(appointment: AppointmentView) {
    const reason = await promptDialog({
      title: t("¿Cancelar la cita?"),
      text: t("El hueco queda libre y el registro se conserva."),
      inputLabel: t("Motivo"),
      maxLength: 500,
      confirmLabel: t("Cancelar cita"),
      dangerous: true,
    });
    if (reason === null) return;
    await act(() => cancelAppointment(companyId, appointment.id, reason),
      "Cita cancelada", "No se pudo cancelar la cita");
  }

  async function noShow(appointment: AppointmentView) {
    const confirmed = await confirmDialog({
      title: t("¿Marcar como no presentado?"),
      text: t("El hueco queda libre y el registro se conserva: es de lo que se discute una demora."),
      confirmLabel: t("Marcar no presentado"),
      dangerous: true,
    });
    if (!confirmed) return;
    await act(() => markAppointmentNoShow(companyId, appointment.id),
      "Marcado como no presentado", "No se pudo marcar como no presentado");
  }

  async function reschedule(appointment: AppointmentView) {
    const value = await promptDialog({
      title: t("¿Mover la cita?"),
      text: t("Nueva hora de inicio, en formato ISO (por ejemplo 2026-09-07T14:30). La duración se conserva."),
      inputLabel: t("Nuevo inicio"),
      required: true,
      maxLength: 40,
      confirmLabel: t("Mover cita"),
    });
    if (value === null) return;
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) {
      notifyError(t("No se pudo mover la cita"), t("La fecha no tiene un formato reconocible."));
      return;
    }
    const end = new Date(parsed.getTime() + appointment.durationMinutes * 60_000);
    await act(() => rescheduleAppointment(companyId, appointment.id, parsed.toISOString(), end.toISOString()),
      "Cita movida", "No se pudo mover la cita");
  }

  /** Solo los botones que el servidor aceptaría: `allowedTransitions` viene de la máquina de estados. */
  function actionsFor(appointment: AppointmentView) {
    const can = (status: AppointmentStatus) => appointment.allowedTransitions.includes(status);
    return [
      ...(can("CONFIRMED") ? [{
        key: "confirm", label: t("Confirmar"), icon: <CheckCircleRounded />,
        onSelect: () => void act(() => confirmAppointment(companyId, appointment.id),
          "Cita confirmada", "No se pudo confirmar la cita"),
      }] : []),
      ...(appointment.status === "REQUESTED" || appointment.status === "CONFIRMED"
        || appointment.status === "RESCHEDULED" ? [{
        key: "move", label: t("Mover"), icon: <ScheduleRounded />,
        onSelect: () => void reschedule(appointment),
      }] : []),
      ...(can("ARRIVED") ? [{
        key: "arrive", label: t("Registrar llegada"), icon: <LocalShippingRounded />,
        onSelect: () => void act(() => arriveAppointment(companyId, appointment.id),
          "Llegada registrada", "No se pudo registrar la llegada"),
      }] : []),
      ...(can("COMPLETED") ? [{
        key: "complete", label: t("Cerrar cita"), icon: <DoneAllRounded />,
        onSelect: () => void act(() => completeAppointment(companyId, appointment.id),
          "Cita cerrada", "No se pudo cerrar la cita"),
      }] : []),
      ...(can("NO_SHOW") ? [{
        key: "no-show", label: t("No presentado"), icon: <PersonOffRounded />,
        dangerous: true, divider: true,
        onSelect: () => void noShow(appointment),
      }] : []),
      ...(can("CANCELLED") ? [{
        key: "cancel", label: t("Cancelar cita"), icon: <CancelRounded />,
        dangerous: true,
        onSelect: () => void cancel(appointment),
      }] : []),
    ];
  }

  const rows = appointmentsQuery.data ?? [];
  const docks = resourcesQuery.data ?? [];
  const outOfService = docks.filter((dock) => !dock.active).length;

  return (
    <>
      <PageHeader
        icon={<EventAvailableRounded />}
        tint={ICON_TINTS["/appointments"]}
        title={t("Citas de muelle")}
        subtitle={t("Qué camión llega a qué puerta y cuándo. Una puerta atiende un vehículo a la vez.")}
        actions={canManage && locationId !== "" && (
          <Button variant="contained" startIcon={<EventAvailableRounded />} onClick={() => setBooking(true)}>
            {t("Reservar")}
          </Button>
        )}
      />

      <Paper variant="outlined" sx={{ p: 2, mb: 2, display: "flex", gap: 2, flexWrap: "wrap", alignItems: "center" }}>
        <TextField
          select size="small" label={t("Sitio")} value={locationId} sx={{ minWidth: 260 }}
          onChange={(e) => setLocationId(e.target.value)}
        >
          <MenuItem value="">{t("Selecciona un sitio")}</MenuItem>
          {(locationsQuery.data?.content ?? []).map((location) => (
            <MenuItem key={location.id} value={location.id}>{location.code} · {location.name}</MenuItem>
          ))}
        </TextField>
        <TextField
          size="small" type="date" label={t("Día")} value={day}
          onChange={(e) => setDay(e.target.value)}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        {locationId !== "" && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <Typography variant="caption" color="text.secondary">
              {t("{{count}} puerta(s)", { count: docks.length })}
            </Typography>
            {outOfService > 0 && (
              <Chip size="small" color="warning" variant="outlined"
                label={t("{{n}} fuera de servicio", { n: outOfService })} />
            )}
          </Box>
        )}
      </Paper>

      {locationId === "" ? (
        <EmptyState
          title={t("Elige un sitio")}
          message={t("El tablero muestra un día de una nave: qué llega a cada puerta y en qué estado está.")}
        />
      ) : appointmentsQuery.isPending ? (
        <LoadingState />
      ) : appointmentsQuery.isError ? (
        <ErrorState message={describeApiError(appointmentsQuery.error as ApiError)} />
      ) : rows.length === 0 ? (
        <EmptyState
          title={t("Sin citas ese día")}
          message={t("Nada reservado en este sitio para la fecha elegida.")}
        />
      ) : (
        <>
          {/* La zona horaria se dice en voz alta: una hora sin decir de dónde es, es cómo un
              camión llega con cinco horas de diferencia. */}
          <Alert severity="info" variant="outlined" sx={{ mb: 1.5 }}>
            {t("Las horas se muestran en la zona de tu navegador ({{zone}}).", {
              zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
            })}
          </Alert>
          <TableContainer component={Paper} variant="outlined">
            <Table size="small" sx={dataTableSx}>
              <TableHead>
                <TableRow>
                  <TableCell>{t("Puerta")}</TableCell>
                  <TableCell>{t("Ventana")}</TableCell>
                  <TableCell>{t("Tipo")}</TableCell>
                  <TableCell>{t("Referencia")}</TableCell>
                  <TableCell>{t("Estado")}</TableCell>
                  {canManage && <TableCell className="actions-col">{t("Acciones")}</TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((appointment) => (
                  <TableRow key={appointment.id}>
                    <TableCell sx={{ fontWeight: 700 }}>{appointment.resourceCode ?? "-"}</TableCell>
                    <TableCell sx={{ fontVariantNumeric: "tabular-nums" }}>
                      {fmtDateTime(appointment.windowStart)} → {fmtDateTime(appointment.windowEnd)}
                      {appointment.rescheduledFromStart && (
                        <Tooltip title={t("Movida desde {{when}}", {
                          when: fmtDateTime(appointment.rescheduledFromStart),
                        })}>
                          <Chip size="small" variant="outlined" sx={{ ml: 1 }}
                            icon={<ScheduleRounded />} label={t("Movida")} />
                        </Tooltip>
                      )}
                    </TableCell>
                    <TableCell>{enumLabel("appointmentPurpose", appointment.purpose)}</TableCell>
                    <TableCell>{appointment.reference ?? "-"}</TableCell>
                    <TableCell>
                      <StatusChip
                        label={enumLabel("appointmentStatus", appointment.status)}
                        tone={STATUS_TONE[appointment.status]}
                      />
                    </TableCell>
                    {canManage && (
                      <TableCell className="actions-col">
                        <ActionMenu items={actionsFor(appointment)} />
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </>
      )}

      {booking && (
        <BookAppointmentDrawer
          companyId={companyId}
          docks={docks.filter((dock) => dock.active)}
          onClose={() => setBooking(false)}
          onBooked={() => { setBooking(false); refresh(); }}
        />
      )}
    </>
  );
}

/**
 * `NO_SHOW` es `overdue` y no `cancelled`: nadie decidió liberar el hueco, alguien no vino — y eso
 * es sobre lo que hay que actuar hoy, no un trámite cerrado.
 */
const STATUS_TONE: Record<AppointmentStatus, StatusTone> = {
  REQUESTED: "open",
  CONFIRMED: "done",
  RESCHEDULED: "inProgress",
  ARRIVED: "inProgress",
  COMPLETED: "done",
  CANCELLED: "cancelled",
  NO_SHOW: "overdue",
};
