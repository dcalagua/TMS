import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, Chip, Paper, Typography,
} from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  BadgeOutlined, CheckRounded, EventNoteRounded, LocalShippingOutlined,
} from "@mui/icons-material";
import {
  cancelWorkAssignment, confirmWorkAssignment, fetchWorkAssignments,
  type WorkAssignmentConflictView, type WorkAssignmentView,
} from "../../shared/api/workAssignmentsApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { FilterBar, PageHeader, SectionHeader, StatusChip } from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { useCompany } from "../../shared/company/CompanyContext";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, today } from "../../lib/locale";
import { R, T, neutralSoft } from "../../theme";

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

  const [date, setDate] = useState(today);
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
      />

      <FilterBar
        value={{ date }}
        defaults={{ date: today() }}
        onChange={(next) => setDate(next.date || today())}
        fields={[{ type: "date", key: "date", label: t("Fecha") }]}
      />

      {assignmentsQuery.isLoading ? (
        <Typography variant="body2" color="text.secondary">{t("Cargando...")}</Typography>
      ) : assignments.length === 0 ? (
        <Alert severity="info" sx={{ borderRadius: `${R.md}px` }}>
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
  const hasConflicts = assignment.conflicts.length > 0;
  return (
    <Paper variant="outlined" sx={{ borderRadius: `${R.lg}px`, overflow: "hidden" }}>
      {/* Cabecera del recurso: vehículo, conductor, estado y si la secuencia es viable. */}
      <Box sx={{
        display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap",
        px: 2, py: 1.5, borderBottom: 1, borderColor: "divider",
      }}>
        <Box aria-hidden sx={(th) => ({
          width: 36, height: 36, flexShrink: 0, borderRadius: `${R.sm}px`, display: "grid", placeItems: "center",
          bgcolor: neutralSoft(th.palette.mode === "dark"), color: "text.secondary", "& svg": { fontSize: 20 },
        })}>
          <LocalShippingOutlined />
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body1" sx={{ fontWeight: 800, lineHeight: 1.25, fontVariantNumeric: "tabular-nums" }}>
            {assignment.vehicleCode ?? assignment.vehicleId}
          </Typography>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, color: "text.secondary" }}>
            <BadgeOutlined aria-hidden sx={{ fontSize: 14 }} />
            <Typography
              variant="body2" color="text.secondary"
              sx={{ fontSize: T.body - 0.5, fontStyle: assignment.driverName ? "normal" : "italic" }}
            >
              {assignment.driverName ?? t("Sin conductor asignado")}
            </Typography>
          </Box>
        </Box>
        <StatusChip
          label={enumLabel("workAssignmentStatus", assignment.status)}
          tone={assignment.status === "CONFIRMED" ? "done" : assignment.status === "CANCELLED" ? "cancelled" : "open"}
        />
        {/* Factible no es permitido: los envíos siguen pasando por sus propios guards al salir. */}
        {assignment.feasible
          ? <Chip size="small" color="success" variant="outlined" label={t("Secuencia viable")} sx={{ fontWeight: 700 }} />
          : <StatusChip tone="inProgress" label={t("{{n}} conflictos", { n: assignment.conflicts.length })} />}
        {canManage && assignment.status === "PLANNED" && (
          <Box sx={{ display: "flex", gap: 1, ml: "auto" }}>
            <Button size="small" variant="outlined" color="error" disabled={busy} onClick={onCancel}>
              {t("Cancelar")}
            </Button>
            <Button
              size="small" variant="contained" disableElevation disabled={busy} onClick={onConfirm}
              startIcon={<CheckRounded />}
            >
              {t("Confirmar")}
            </Button>
          </Box>
        )}
      </Box>

      <Box sx={{
        p: 2, display: "grid", gap: 2.5, alignItems: "start",
        gridTemplateColumns: { xs: "1fr", lg: hasConflicts ? "minmax(0,1fr) 400px" : "1fr" },
      }}>
        {assignment.trips.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {t("Sin envíos todavía.")}
          </Typography>
        ) : (
          <Box>
            {assignment.trips.map((trip) => (
              <Box key={trip.tripId}>
                {/* El desplazamiento va ENTRE dos envíos, así que se dibuja antes del segundo.
                    `null` en el primero es correcto; `null` después significa que el tramo no se
                    pudo medir, y eso se dice - no se pinta como cero. */}
                {trip.sequence > 1 && (
                  <Box sx={{ ml: "13px", pl: 1.25, py: 0.5, borderLeft: "1.5px solid", borderColor: "divider" }}>
                    <Typography
                      variant="caption"
                      sx={{
                        display: "block", lineHeight: 1.4,
                        color: trip.repositionMinutes === null ? "warning.dark" : "text.secondary",
                        fontWeight: trip.repositionMinutes === null ? 700 : 500,
                      }}
                    >
                      {trip.repositionMinutes === null
                        ? t("↓ desplazamiento sin medir")
                        : t("↓ {{n}} min de desplazamiento", { n: trip.repositionMinutes })}
                    </Typography>
                  </Box>
                )}
                <Paper
                  variant="outlined"
                  sx={{
                    px: 1.5, py: 1, borderRadius: `${R.md}px`,
                    display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap",
                  }}
                >
                  <Box sx={(th) => ({
                    width: 24, height: 24, borderRadius: "50%", flexShrink: 0, display: "grid", placeItems: "center",
                    bgcolor: alpha(th.palette.primary.main, th.palette.mode === "dark" ? 0.22 : 0.12),
                    color: "primary.main", fontWeight: 800, fontSize: T.label, fontVariantNumeric: "tabular-nums",
                  })}>
                    {trip.sequence}
                  </Box>
                  <Typography variant="body2" sx={{ fontWeight: 800, fontVariantNumeric: "tabular-nums" }}>
                    {trip.shipmentNumber ?? trip.tripId}
                  </Typography>
                  <Typography
                    variant="caption" color="text.secondary"
                    sx={{ ml: "auto", textAlign: "right", fontVariantNumeric: "tabular-nums" }}
                  >
                    {trip.plannedStart && trip.plannedEnd
                      ? `${fmtDateTime(trip.plannedStart)} → ${fmtDateTime(trip.plannedEnd)}`
                      : t("Sin ventana conocida")}
                  </Typography>
                </Paper>
              </Box>
            ))}
          </Box>
        )}

        {hasConflicts && (
          <Box>
            <SectionHeader title={t("Conflictos")} level={4} />
            <Box sx={{ display: "grid", gap: 1 }}>
              {assignment.conflicts.map((conflict: WorkAssignmentConflictView, index) => (
                <Alert
                  key={`${conflict.reason}-${index}`} severity="warning"
                  sx={{ borderRadius: `${R.md}px`, border: "none", "& .MuiAlert-icon": { fontSize: 18 } }}
                >
                  <Typography variant="body2" sx={{ fontWeight: 700, color: "warning.dark" }}>
                    {conflict.sequence > 0 ? `#${conflict.sequence} · ` : ""}
                    {enumLabel("resourceRejectionReason", conflict.reason)}
                  </Typography>
                  <Typography variant="caption" color="text.primary" sx={{ display: "block", lineHeight: 1.45 }}>
                    {conflict.detail}
                  </Typography>
                </Alert>
              ))}
            </Box>
          </Box>
        )}
      </Box>
    </Paper>
  );
}
