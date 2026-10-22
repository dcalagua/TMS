import { Box, Button, Paper, Typography } from "@mui/material";
import {
  CloudUploadRounded, DoNotDisturbOnRounded, ErrorOutlineRounded, FlagRounded, Inventory2Outlined,
  PhotoCameraRounded, PinDropRounded, PlayArrowRounded, ReportProblemRounded,
} from "@mui/icons-material";
import type { ReactNode } from "react";
import type { PortalStopTransition } from "../../shared/api/portalApi";
import type { StopExecutionStatus } from "../../shared/api/planningApi";
import { StatusChip } from "../../shared/ui/components";
import { DELIVERY_RESULT_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtTime } from "../../lib/locale";
import { R } from "../../theme";
import type { ShownIncident, ShownOrder, ShownStop } from "./offline/overlay";
import { PORTAL_STOP_ACTIONS } from "./portalExecution";

/**
 * Lo que el transportista hace en una parada desde el portal (V70): dónde está —sin iniciar, en
 * el punto, descargando, atendida—, los botones que el servidor permite a continuación, y los
 * pedidos que se entregan ahí con su resultado.
 */

const STATUS_TONE: Record<StopExecutionStatus, "neutral" | "inProgress" | "done" | "overdue"> = {
  PENDING: "neutral",
  ARRIVED: "inProgress",
  IN_SERVICE: "inProgress",
  COMPLETED: "done",
  SKIPPED: "neutral",
  FAILED: "overdue",
};

const ACTION_ICON: Record<PortalStopTransition, ReactNode> = {
  arrive: <PinDropRounded />,
  service: <PlayArrowRounded />,
  complete: <FlagRounded />,
  fail: <DoNotDisturbOnRounded />,
};

interface StopExecutionProps {
  stop: ShownStop;
  /** El usuario tiene `portal.stop:execute` y el viaje está en ruta. */
  canExecute: boolean;
  /** El usuario tiene `portal.delivery:report` y el viaje está en ruta. */
  canReport: boolean;
  busy: boolean;
  onTransition: (transition: PortalStopTransition) => void;
  onDelivery: (order: ShownOrder) => void;
  onIncident: () => void;
}

export function StopExecution({ stop, canExecute, canReport, busy, onTransition, onDelivery, onIncident }: StopExecutionProps) {
  const status = stop.executionStatus;
  if (!status) return null;
  const allowed = new Set(stop.allowedExecutionTransitions ?? []);
  const actions = canExecute ? PORTAL_STOP_ACTIONS.filter((action) => allowed.has(action.target)) : [];
  const reached = status !== "PENDING";
  const times = [
    stop.actualArrivalAt ? t("Llegada {{time}}", { time: fmtTime(stop.actualArrivalAt) }) : null,
    stop.serviceStartedAt ? t("Descarga {{time}}", { time: fmtTime(stop.serviceStartedAt) }) : null,
    stop.actualDepartureAt ? t("Salida {{time}}", { time: fmtTime(stop.actualDepartureAt) }) : null,
  ].filter(Boolean).join(" · ");

  return (
    <Box sx={{ display: "grid", gap: 1 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
        <StatusChip label={enumLabel("stopExecutionStatus", status)} tone={STATUS_TONE[status]} />
        {stop.pendingSync && <StatusChip tone="inProgress" icon={<CloudUploadRounded />} label={t("Pendiente de sincronizar")} />}
        {stop.syncFailed && <StatusChip tone="overdue" icon={<ErrorOutlineRounded />} label={t("No se pudo sincronizar")} />}
        {times && (
          <Typography color="text.secondary" sx={{ fontSize: 14, fontVariantNumeric: "tabular-nums" }}>{times}</Typography>
        )}
      </Box>

      {actions.length > 0 && (
        <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(140px, 1fr))", gap: 1 }}>
          {actions.map((action) => (
            <Button
              key={action.transition}
              variant={action.transition === "fail" ? "outlined" : "contained"}
              color={action.transition === "fail" ? "warning" : action.transition === "complete" ? "success" : "primary"}
              startIcon={ACTION_ICON[action.transition]}
              disabled={busy}
              onClick={() => onTransition(action.transition)}
              sx={{ minHeight: 52, fontSize: 16, fontWeight: 800, textTransform: "none" }}
            >
              {t(action.label)}
            </Button>
          ))}
        </Box>
      )}

      {stop.orders.length > 0 && (
        <Box component="ul" aria-label={t("Pedidos de la parada")} sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 0.75 }}>
          {stop.orders.map((order) => (
            <OrderRow
              key={order.orderId} order={order} canReport={canReport && reached} busy={busy}
              onOpen={() => onDelivery(order)}
            />
          ))}
        </Box>
      )}
      {canReport && !reached && stop.orders.length > 0 && (
        <Typography color="text.secondary" sx={{ fontSize: 14 }}>
          {t("Marca «Llegué» para registrar la entrega de los pedidos.")}
        </Typography>
      )}

      {canExecute && (
        <Button
          variant="text" startIcon={<ReportProblemRounded />} onClick={onIncident} disabled={busy}
          sx={{ justifySelf: "start", minHeight: 44, fontWeight: 700, textTransform: "none" }}
        >
          {t("Reportar incidencia en esta parada")}
        </Button>
      )}
    </Box>
  );
}

function OrderRow({ order, canReport, busy, onOpen }: { order: ShownOrder; canReport: boolean; busy: boolean; onOpen: () => void }) {
  const delivery = order.delivery;
  return (
    <Paper component="li" variant="outlined" sx={{
      borderRadius: `${R.md}px`, px: 1.5, py: 1, display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap",
      borderLeft: "5px solid",
      borderLeftColor: delivery ? `${DELIVERY_RESULT_TONE[delivery.result] === "done" ? "success" : DELIVERY_RESULT_TONE[delivery.result] === "overdue" ? "error" : "info"}.main` : "divider",
    }}>
      <Inventory2Outlined aria-hidden sx={{ fontSize: 20, color: "text.secondary" }} />
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography sx={{ fontWeight: 800, fontSize: 16, fontVariantNumeric: "tabular-nums" }}>{order.orderNumber}</Typography>
        {order.customerName && <Typography color="text.secondary" sx={{ fontSize: 14 }}>{order.customerName}</Typography>}
        <Box sx={{ display: "flex", gap: 0.75, alignItems: "center", flexWrap: "wrap", mt: 0.25 }}>
          {delivery ? (
            <StatusChip label={enumLabel("deliveryResult", delivery.result)} tone={DELIVERY_RESULT_TONE[delivery.result]} />
          ) : (
            <Typography color="text.secondary" sx={{ fontSize: 14 }}>{t("Sin registrar")}</Typography>
          )}
          {order.pendingSync && <StatusChip tone="inProgress" icon={<CloudUploadRounded />} label={t("Pendiente de sincronizar")} />}
          {order.syncFailed && <StatusChip tone="overdue" icon={<ErrorOutlineRounded />} label={t("No se pudo sincronizar")} />}
          {delivery && delivery.evidenceCount > 0 && (
            <Typography color="text.secondary" sx={{ fontSize: 14, display: "flex", alignItems: "center", gap: 0.25 }}
              aria-label={t("{{n}} fotos o firmas", { n: delivery.evidenceCount })}>
              <PhotoCameraRounded sx={{ fontSize: 16 }} /> {delivery.evidenceCount}
            </Typography>
          )}
          {delivery && delivery.source !== "CARRIER_PORTAL" && (
            <Typography color="text.secondary" sx={{ fontSize: 13 }}>{t("Registrado por la oficina")}</Typography>
          )}
        </Box>
      </Box>
      {canReport && (
        <Button
          variant={delivery ? "outlined" : "contained"} onClick={onOpen} disabled={busy}
          aria-label={`${delivery ? t("Corregir entrega") : t("Registrar entrega")} ${order.orderNumber}`}
          sx={{ minHeight: 48, fontWeight: 800, textTransform: "none" }}
        >
          {delivery ? t("Corregir") : t("Registrar entrega")}
        </Button>
      )}
    </Paper>
  );
}

/** Las incidencias del viaje: las del transportista y las de la oficina, abiertas o resueltas. */
export function TripIncidents({ incidents }: { incidents: ShownIncident[] }) {
  if (incidents.length === 0) return null;
  return (
    <Paper variant="outlined" sx={{ p: 1.5, borderRadius: `${R.lg}px`, display: "grid", gap: 1 }}>
      <Typography component="h2" sx={{ fontWeight: 800, fontSize: 17 }}>{t("Incidencias")}</Typography>
      <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 0.75 }}>
        {incidents.map((incident) => (
          <Box component="li" key={incident.id} sx={{ display: "grid", gap: 0.25 }}>
            <Box sx={{ display: "flex", gap: 0.75, alignItems: "center", flexWrap: "wrap" }}>
              <Typography sx={{ fontWeight: 700, fontSize: 15 }}>{enumLabel("tripExceptionType", incident.exceptionType)}</Typography>
              <StatusChip
                label={incident.status === "OPEN" ? t("Abierta") : t("Resuelta")}
                tone={incident.status === "OPEN" ? "overdue" : "done"}
              />
              {incident.pendingSync && <StatusChip tone="inProgress" icon={<CloudUploadRounded />} label={t("Pendiente de sincronizar")} />}
            </Box>
            <Typography color="text.secondary" sx={{ fontSize: 14 }}>
              {[incident.stopSequence !== null ? t("Parada {{n}}", { n: incident.stopSequence }) : t("Todo el viaje"),
                fmtDateTime(incident.reportedAt), incident.notes].filter(Boolean).join(" · ")}
            </Typography>
          </Box>
        ))}
      </Box>
    </Paper>
  );
}
