import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, Chip, Paper, TextField, Typography,
} from "@mui/material";
import { EventNoteRounded } from "@mui/icons-material";
import {
  cancelWorkAssignment, confirmWorkAssignment, fetchWorkAssignments,
  type WorkAssignmentConflictView, type WorkAssignmentView,
} from "../../shared/api/workAssignmentsApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { PageHeader, SectionHeader, StatusChip } from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { useCompany } from "../../shared/company/CompanyContext";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";

/**
 * El día de cada conductor y vehículo (migración V47).
 *
 * <h2>Deliberadamente no es un Gantt</h2>
 * Una línea por recurso y una fila por envío, con el tiempo de desplazamiento entre ellos escrito
 * como un número. Un Gantt de verdad — arrastrar, zoom, carriles — es una pantalla entera de trabajo
 * y no es lo que hace falta para responder la pregunta operativa: **¿este día se puede hacer, y si
 * no, por qué?**
 *
 * <h2>Los conflictos se nombran</h2>
 * Nunca "no disponible". Una licencia vencida, un camión en taller y un hueco demasiado corto para
 * conducir son tres problemas que resuelven tres personas distintas, y la frase que los explica la
 * compone el servidor junto a las cifras que la sostienen.
 *
 * <h2>Factible no es permitido</h2>
 * Un día sin conflictos sigue sin autorizar nada: los envíos se despachan de uno en uno y todo guard
 * que hoy rechaza una salida la sigue rechazando.
 */
export function WorkAssignmentsPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("fleet.work_assignment:manage");
  const queryClient = useQueryClient();

  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [busy, setBusy] = useState(false);

  const assignmentsQuery = useQuery({
    queryKey: ["work-assignments", companyId, date],
    queryFn: ({ signal }) => fetchWorkAssignments(companyId, date, signal),
    placeholderData: keepPreviousData,
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["work-assignments", companyId] });
  }

  async function run(action: () => Promise<unknown>, success: string) {
    setBusy(true);
    try {
      await action();
      notifySuccess(success);
      refresh();
    } catch (error) {
      // El servidor nombra cada conflicto en el rechazo. Traducirlo a "no se pudo" tiraría justo la
      // parte que dice qué arreglar.
      notifyError(t("No se pudo completar"), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  const assignments = assignmentsQuery.data ?? [];

  return (
    <>
      <PageHeader
        icon={<EventNoteRounded />}
        tint={ICON_TINTS["/work-assignments"]}
        title={t("Días de trabajo")}
        subtitle={t("Qué hace cada conductor y vehículo en el día, en orden, con el tiempo de desplazamiento entre envíos.")}
        onRefresh={refresh}
        refreshing={assignmentsQuery.isFetching}
        actions={
          <TextField
            size="small" type="date" label={t("Fecha")} value={date}
            onChange={(e) => setDate(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
        }
      />

      {assignmentsQuery.isLoading ? (
        <Typography variant="body2" color="text.secondary">{t("Cargando...")}</Typography>
      ) : assignments.length === 0 ? (
        <Alert severity="info" variant="outlined">
          {t("Nadie tiene trabajo planificado para este día.")}
        </Alert>
      ) : (
        <Box sx={{ display: "grid", gap: 2 }}>
          {assignments.map((assignment) => (
            <ResourceDay
              key={assignment.id}
              assignment={assignment}
              canManage={canManage}
              busy={busy}
              onConfirm={() => void run(
                () => confirmWorkAssignment(companyId, assignment.id), t("Día confirmado"))}
              onCancel={async () => {
                const confirmed = await confirmDialog({
                  title: t("¿Cancelar el día?"),
                  text: t("El vehículo y el conductor quedan libres para otro día de trabajo."),
                  confirmLabel: t("Sí, cancelar"),
                  dangerous: true,
                });
                if (confirmed) {
                  void run(() => cancelWorkAssignment(companyId, assignment.id), t("Día cancelado"));
                }
              }}
            />
          ))}
        </Box>
      )}
    </>
  );
}

function ResourceDay({
  assignment, canManage, busy, onConfirm, onCancel,
}: {
  assignment: WorkAssignmentView;
  canManage: boolean;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap", mb: 1.5 }}>
        <Typography variant="body1" sx={{ fontWeight: 800 }}>
          {assignment.vehicleCode ?? assignment.vehicleId}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {assignment.driverName ?? t("Sin conductor asignado")}
        </Typography>
        <StatusChip
          label={enumLabel("workAssignmentStatus", assignment.status)}
          tone={assignment.status === "CONFIRMED" ? "done" : assignment.status === "CANCELLED" ? "cancelled" : "open"}
        />
        {/* Factible no es permitido: los envíos siguen pasando por sus propios guards al salir. */}
        {assignment.feasible
          ? <Chip size="small" color="success" variant="outlined" label={t("Secuencia viable")} />
          : <Chip size="small" color="warning" label={t("{{n}} conflictos", { n: assignment.conflicts.length })} />}
        <Box sx={{ flex: 1 }} />
        {canManage && assignment.status === "PLANNED" && (
          <>
            <Button size="small" variant="contained" disabled={busy} onClick={onConfirm}>
              {t("Confirmar")}
            </Button>
            <Button size="small" variant="outlined" color="error" disabled={busy} onClick={onCancel}>
              {t("Cancelar")}
            </Button>
          </>
        )}
      </Box>

      {assignment.trips.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          {t("Sin envíos todavía.")}
        </Typography>
      ) : (
        <Box sx={{ display: "grid", gap: 0.75 }}>
          {assignment.trips.map((trip) => (
            <Box key={trip.tripId}>
              {/* El desplazamiento va ENTRE dos envíos, así que se dibuja antes del segundo.
                  `null` en el primero es correcto; `null` después significa que el tramo no se
                  pudo medir, y eso se dice - no se pinta como cero. */}
              {trip.sequence > 1 && (
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", pl: 4, py: 0.25 }}>
                  {trip.repositionMinutes === null
                    ? t("↓ desplazamiento sin medir")
                    : t("↓ {{n}} min de desplazamiento", { n: trip.repositionMinutes })}
                </Typography>
              )}
              <Paper
                variant="outlined"
                sx={{ p: 1.25, display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}
              >
                <Box sx={{
                  width: 26, height: 26, borderRadius: "50%", flexShrink: 0, display: "grid",
                  placeItems: "center", bgcolor: "primary.main", color: "primary.contrastText",
                  fontWeight: 800, fontSize: 12,
                }}>
                  {trip.sequence}
                </Box>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>
                  {trip.shipmentNumber ?? trip.tripId}
                </Typography>
                <Box sx={{ flex: 1 }} />
                <Typography variant="caption" color="text.secondary">
                  {trip.plannedStart && trip.plannedEnd
                    ? `${fmtDateTime(trip.plannedStart)} → ${fmtDateTime(trip.plannedEnd)}`
                    : t("Sin ventana conocida")}
                </Typography>
              </Paper>
            </Box>
          ))}
        </Box>
      )}

      {assignment.conflicts.length > 0 && (
        <Box sx={{ mt: 1.5 }}>
          <SectionHeader title={t("Conflictos")} level={4} />
          <Box sx={{ display: "grid", gap: 0.5 }}>
            {assignment.conflicts.map((conflict: WorkAssignmentConflictView, index) => (
              <Alert key={`${conflict.reason}-${index}`} severity="warning" variant="outlined">
                <Typography variant="body2" sx={{ fontWeight: 700 }}>
                  {conflict.sequence > 0 ? `#${conflict.sequence} · ` : ""}
                  {enumLabel("resourceRejectionReason", conflict.reason)}
                </Typography>
                <Typography variant="caption" color="text.secondary">{conflict.detail}</Typography>
              </Alert>
            ))}
          </Box>
        </Box>
      )}
    </Paper>
  );
}
