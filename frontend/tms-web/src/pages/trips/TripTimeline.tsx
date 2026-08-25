import { Box, Typography } from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  PlayArrowRounded, FlagRounded, PlaceRounded, BuildRounded, DoneRounded,
  SkipNextRounded, ErrorOutlineRounded, EditRounded, CircleRounded, LocalOfferRounded,
} from "@mui/icons-material";
import type { TransportEventType, TransportEventView } from "../../shared/api/planningApi";
import { EmptyState, LoadingState } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";

interface TripTimelineProps {
  events: TransportEventView[];
  loading?: boolean;
}

/**
 * Qué le fue pasando al viaje, en orden.
 *
 * Se pinta desde su propia consulta y no desde el detalle del viaje: crece durante todo el día,
 * se lee más de lo que se acciona, y separarla es lo que evita que cada acción sobre una parada
 * reenvíe cuarenta entradas.
 *
 * Cada fila lleva dos tiempos. `eventTime` es cuándo pasó y es el que manda la vertical; el
 * `recordedAt` solo aparece cuando difiere, porque la diferencia entre los dos es información
 * real —una llegada de las 11:04 tecleada a las 11:40— y esconderla haría que un rastro
 * introducido en diferido pareciera un rastro en vivo.
 */
export function TripTimeline({ events, loading }: TripTimelineProps) {
  if (loading) return <LoadingState minHeight={160} />;
  if (events.length === 0) {
    return <EmptyState title={t("Sin eventos")} message={t("Todavía no ha pasado nada en este viaje.")} />;
  }

  return (
    <Box sx={{ position: "relative", pl: 3.5 }}>
      {/* La línea vertical que une los hitos. Decorativa: lo que se lee son las filas. */}
      <Box aria-hidden sx={{
        position: "absolute", left: 13, top: 8, bottom: 8, width: "2px",
        bgcolor: "divider", borderRadius: 1,
      }} />

      {events.map((event) => {
        const Icon = EVENT_ICON[event.eventType] ?? CircleRounded;
        const color = EVENT_COLOR[event.eventType] ?? "text.disabled";
        const sameTime = fmtDateTime(event.eventTime) === fmtDateTime(event.recordedAt);
        return (
          <Box key={event.id} sx={{ position: "relative", pb: 2.5 }}>
            <Box sx={(th) => {
              const [k, sub = "main"] = String(color).split(".");
              const palette = th.palette as unknown as Record<string, Record<string, string>>;
              const main = palette[k]?.[sub] ?? th.palette.text.disabled;
              return {
                position: "absolute", left: -27, top: 1,
                width: 28, height: 28, borderRadius: "50%", display: "grid", placeItems: "center",
                bgcolor: alpha(main, 0.16), color: main,
                border: "2px solid", borderColor: th.palette.background.paper,
                "& svg": { fontSize: 16 },
              };
            }}>
              <Icon />
            </Box>

            <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.35 }}>
              {enumLabel("transportEventType", event.eventType)}
              {event.stopSequence !== null && (
                <Box component="span" sx={{ color: "text.secondary", fontWeight: 500 }}>
                  {" · "}
                  {event.stopSequence}. {event.stopDestinationName ?? event.stopDestinationCode ?? ""}
                </Box>
              )}
            </Typography>

            <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
              {fmtDateTime(event.eventTime)}
              {!sameTime && ` · ${t("registrado")} ${fmtDateTime(event.recordedAt)}`}
              {event.actorName && ` · ${event.actorName}`}
            </Typography>

            {event.notes && (
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.4 }}>{event.notes}</Typography>
            )}
          </Box>
        );
      })}
    </Box>
  );
}

/** Un icono por tipo de evento. Un mapa parcial: un tipo nuevo del backend cae a un punto neutro
 * en lugar de romper la línea de tiempo. */
const EVENT_ICON: Partial<Record<TransportEventType, typeof CircleRounded>> = {
  TRIP_CONFIRMED: FlagRounded,
  TRIP_READY: FlagRounded,
  TRIP_DISPATCHED: PlayArrowRounded,
  TRIP_COMPLETED: DoneRounded,
  TRIP_CANCELLED: ErrorOutlineRounded,
  ARRIVED_AT_STOP: PlaceRounded,
  SERVICE_STARTED: BuildRounded,
  STOP_COMPLETED: DoneRounded,
  STOP_SKIPPED: SkipNextRounded,
  STOP_FAILED: ErrorOutlineRounded,
  DELIVERY_RECORDED: EditRounded,
  TENDER_SENT: LocalOfferRounded,
  TENDER_ACCEPTED: DoneRounded,
  TENDER_REJECTED: ErrorOutlineRounded,
  TENDER_EXPIRED: SkipNextRounded,
  TENDER_CANCELLED: SkipNextRounded,
  EXCEPTION_REPORTED: ErrorOutlineRounded,
  EXCEPTION_RESOLVED: DoneRounded,
};

const EVENT_COLOR: Partial<Record<TransportEventType, string>> = {
  TRIP_CONFIRMED: "info.main",
  TRIP_READY: "info.main",
  TRIP_DISPATCHED: "warning.main",
  TRIP_COMPLETED: "success.main",
  TRIP_CANCELLED: "error.main",
  ARRIVED_AT_STOP: "info.main",
  SERVICE_STARTED: "info.main",
  STOP_COMPLETED: "success.main",
  STOP_SKIPPED: "warning.main",
  STOP_FAILED: "error.main",
  DELIVERY_RECORDED: "primary.main",
  TENDER_SENT: "warning.main",
  TENDER_ACCEPTED: "success.main",
  TENDER_REJECTED: "error.main",
  TENDER_EXPIRED: "warning.main",
  TENDER_CANCELLED: "text.disabled",
  EXCEPTION_REPORTED: "error.main",
  EXCEPTION_RESOLVED: "success.main",
};
