import { Box, Chip, Paper, Typography } from "@mui/material";
import { Link } from "react-router-dom";
import { ReportProblemRounded, ScheduleRounded, LocalShippingRounded, BlockRounded, InfoOutlined } from "@mui/icons-material";
import type {
  ControlTowerAdvisoryView,
  ControlTowerBlockerView, ControlTowerExceptionView, ControlTowerStopView, ControlTowerWorkloadView,
} from "../../shared/api/controlTowerApi";
import { AppCard, StatusChip } from "../../shared/ui/components";
import { STOP_EXECUTION_TONE, TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtMinutes, fmtPercent, fmtTime } from "../../lib/locale";

/** Un panel de la torre siempre dice de cuántos son los que enseña: "los peores veinte de
 * cuarenta y siete" es una frase distinta de "hay veinte". */
function PanelTitle({ icon, label, shown, total }: { icon: React.ReactNode; label: string; shown: number; total: number }) {
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
      {icon}
      {label}
      {total > shown && (
        <Chip size="small" variant="outlined" label={t("{{shown}} de {{total}}", { shown, total })} sx={{ height: 20, fontSize: 10.5 }} />
      )}
    </Box>
  );
}

/**
 * Los envíos más cargados del día, con el peor de sus tres ejes de capacidad.
 *
 * El porcentaje lo decide el servidor y es *el peor* de los tres, no un promedio: lo que dice si
 * un camión está lleno es la dimensión que primero se acaba. Un `null` es "no sabemos cuánto va
 * lleno" y se dice así — pintarlo como 0% se leería como un camión vacío.
 */
export function WorkloadPanel({ items, total }: { items: ControlTowerWorkloadView[]; total: number }) {
  return (
    <AppCard title={<PanelTitle icon={<LocalShippingRounded sx={{ fontSize: 19, color: "text.disabled" }} />} label={t("Carga de los envíos")} shown={items.length} total={total} />}>
      {items.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("No hay envíos en curso.")}</Typography>
      ) : (
        <Box sx={{ display: "grid", gap: 1 }}>
          {items.map(({ trip, percentUsed }) => (
            <Paper
              key={trip.id}
              component={Link}
              to={`/trips/${trip.id}`}
              variant="outlined"
              sx={{
                p: 1.25, display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap",
                textDecoration: "none", color: "text.primary",
                "&:hover": { borderColor: "primary.main" },
              }}
            >
              <Box sx={{ flex: 1, minWidth: 140 }}>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>{trip.shipmentNumber}</Typography>
                <Typography variant="caption" color="text.secondary" noWrap>
                  {trip.vehicleLicensePlate ?? t("Sin vehículo asignado")}
                  {trip.carrierName && ` · ${trip.carrierName}`}
                </Typography>
              </Box>
              <StatusChip label={enumLabel("tripStatus", trip.status)} tone={TRIP_STATUS_TONE[trip.status]} />
              <Typography
                variant="body2"
                sx={{
                  fontWeight: 800, fontVariantNumeric: "tabular-nums", minWidth: 52, textAlign: "right",
                  color: percentUsed === null ? "text.disabled"
                    : percentUsed > 100 ? "error.main"
                    : percentUsed >= 85 ? "warning.main" : "text.primary",
                }}
              >
                {percentUsed === null ? t("n/d") : fmtPercent(percentUsed)}
              </Typography>
            </Paper>
          ))}
        </Box>
      )}
    </AppCard>
  );
}

/**
 * Las incidencias abiertas del día, a nivel de envío a propósito: el panel dice qué envío abrir,
 * y es el espacio de trabajo el que resuelve la parada.
 */
export function ExceptionsPanel({ items, total }: { items: ControlTowerExceptionView[]; total: number }) {
  return (
    <AppCard title={<PanelTitle icon={<ReportProblemRounded sx={{ fontSize: 19, color: "error.main" }} />} label={t("Incidencias abiertas")} shown={items.length} total={total} />}>
      {items.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("Ninguna incidencia abierta hoy.")}</Typography>
      ) : (
        <Box sx={{ display: "grid", gap: 1 }}>
          {items.map((exception) => (
            <Paper
              key={exception.id}
              component={Link}
              to={`/trips/${exception.tripId}`}
              variant="outlined"
              sx={{
                p: 1.25, textDecoration: "none", color: "text.primary", display: "block",
                borderLeft: "3px solid", borderLeftColor: "error.main",
                "&:hover": { borderColor: "error.main" },
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>
                  {enumLabel("tripExceptionType", exception.exceptionType)}
                </Typography>
                <Typography variant="caption" color="text.secondary">{exception.shipmentNumber ?? ""}</Typography>
                <Box sx={{ flex: 1 }} />
                <Typography variant="caption" color="text.secondary">{fmtDateTime(exception.reportedAt)}</Typography>
              </Box>
              {exception.notes && (
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.25 }}>
                  {exception.notes}
                </Typography>
              )}
            </Paper>
          ))}
        </Box>
      )}
    </AppCard>
  );
}

/**
 * Las paradas que siguen sin resolverse en envíos que ya están fuera: el trabajo que queda en la
 * calle.
 *
 * `minutesPastWindow` viene del servidor, que es quien sabe a qué día y a qué zona horaria
 * pertenece una ventana guardada como hora local sin fecha. Calcularlo aquí sería adivinarlo.
 */
export function OutstandingStopsPanel({ items, total }: { items: ControlTowerStopView[]; total: number }) {
  return (
    <AppCard title={<PanelTitle icon={<ScheduleRounded sx={{ fontSize: 19, color: "text.disabled" }} />} label={t("Paradas pendientes")} shown={items.length} total={total} />}>
      {items.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("No queda ninguna parada pendiente.")}</Typography>
      ) : (
        <Box sx={{ display: "grid", gap: 1 }}>
          {items.map((stop) => {
            const late = stop.minutesPastWindow !== null && stop.minutesPastWindow > 0;
            return (
              <Paper
                key={stop.stopId}
                component={Link}
                to={`/trips/${stop.tripId}`}
                variant="outlined"
                sx={{
                  p: 1.25, textDecoration: "none", color: "text.primary", display: "block",
                  ...(late ? { borderLeft: "3px solid", borderLeftColor: "warning.main" } : {}),
                  "&:hover": { borderColor: "primary.main" },
                }}
              >
                <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                  <Typography variant="body2" sx={{ fontWeight: 700 }}>
                    {stop.sequence}. {stop.destinationName ?? stop.destinationCode ?? ""}
                  </Typography>
                  <StatusChip
                    label={enumLabel("stopExecutionStatus", stop.executionStatus)}
                    tone={STOP_EXECUTION_TONE[stop.executionStatus]}
                  />
                  <Box sx={{ flex: 1 }} />
                  {late && (
                    <Typography variant="caption" sx={{ fontWeight: 800, color: "warning.main" }}>
                      +{fmtMinutes(stop.minutesPastWindow)}
                    </Typography>
                  )}
                </Box>
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.25 }}>
                  {stop.shipmentNumber}
                  {stop.vehicleLicensePlate && ` · ${stop.vehicleLicensePlate}`}
                  {stop.windowEndsAt && ` · ${t("Ventana hasta")} ${fmtTime(stop.windowEndsAt)}`}
                </Typography>
              </Paper>
            );
          })}
        </Box>
      )}
    </AppCard>
  );
}

/**
 * Lo que impedirá salir a un camión hoy, antes de que se lo impida.
 *
 * <h2>Por qué este panel es distinto de los demás</h2>
 * Todos los otros cuentan lo que **ya pasó**: una parada fuera de ventana, una salida ya tarde, una
 * incidencia que alguien levantó. Éste cuenta lo que está **a punto** de pasar — los estados que
 * hacen que `dispatch` se niegue — para que un despachador se entere a las 06:00 y no en la puerta.
 *
 * Aquí no se inventa ninguna regla: cada motivo es un rechazo que ya existe en el servicio, en el
 * agregado y en la base de datos. Un envío de esta lista **realmente no puede salir**.
 */
export function BlockersPanel({ items, total }: { items: ControlTowerBlockerView[]; total: number }) {
  return (
    <AppCard title={<PanelTitle icon={<BlockRounded sx={{ fontSize: 19, color: "warning.main" }} />} label={t("No pueden salir")} shown={items.length} total={total} />}>
      {items.length === 0 ? (
        // Se dice en voz alta. "No hay nada atascado" es un dato que un despachador quiere leer,
        // no deducir de un panel vacío.
        <Typography variant="body2" color="text.secondary">{t("Ningún envío bloqueado hoy.")}</Typography>
      ) : (
        <Box sx={{ display: "grid", gap: 1 }}>
          {items.map((blocker) => (
            <Paper
              key={`${blocker.tripId}-${blocker.reason}`}
              component={Link}
              to={`/trips/${blocker.tripId}`}
              variant="outlined"
              sx={{
                p: 1.25, textDecoration: "none", color: "text.primary", display: "block",
                borderLeft: "3px solid", borderLeftColor: "warning.main",
                "&:hover": { borderColor: "warning.main" },
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>
                  {enumLabel("blockerReason", blocker.reason)}
                </Typography>
                <Typography variant="caption" color="text.secondary">{blocker.shipmentNumber}</Typography>
              </Box>
              <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.25 }}>
                {blocker.detail}
              </Typography>
            </Paper>
          ))}
        </Box>
      )}
    </AppCard>
  );
}

/**
 * Lo que conviene saber y no detiene nada (JOB 23, Control Tower V3).
 *
 * <h2>Por qué es un panel aparte y no más filas en "No pueden salir"</h2>
 * Un bloqueador es un estado que hace que el despacho se niegue: el camión no se mueve hasta que
 * alguien lo arregle. Un aviso es algo que un supervisor debería saber y sobre lo que puede
 * razonablemente no hacer nada hoy. En cuanto este panel haya dado la voz de alarma por una
 * diferencia de cuarenta céntimos, el camión que de verdad no puede salir será una fila entre
 * cuarenta. Por eso van separados, con distinto color y distinto contador.
 *
 * <h2>La torre no cierra nada de esto</h2>
 * Cada fila enlaza al módulo dueño del hecho — una discrepancia se acepta o rechaza en
 * Liquidaciones — y aquí no hay botón para resolverla. Dos registros de una misma disputa se
 * separarían la primera vez que alguien resolviera el que no era.
 */
export function AdvisoriesPanel({ items, total }: { items: ControlTowerAdvisoryView[]; total: number }) {
  return (
    <AppCard title={<PanelTitle icon={<InfoOutlined sx={{ fontSize: 19, color: "info.main" }} />} label={t("Conviene saber")} shown={items.length} total={total} />}>
      {items.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("Nada pendiente de mirar hoy.")}</Typography>
      ) : (
        <Box sx={{ display: "grid", gap: 1 }}>
          {items.map((advisory) => (
            <Paper
              key={`${advisory.type}-${advisory.sourceId}`}
              component={Link}
              to={advisory.type === "SETTLEMENT_DISCREPANCY_OPEN"
                ? `/settlement?discrepancy=${advisory.sourceId}`
                : `/trips/${advisory.tripId}`}
              variant="outlined"
              sx={{
                p: 1.25, textDecoration: "none", color: "text.primary", display: "block",
                // Azul y no ámbar: el color es la mitad del mensaje de que esto no detiene nada.
                borderLeft: "3px solid", borderLeftColor: "info.main",
                "&:hover": { borderColor: "info.main" },
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>
                  {enumLabel("advisoryType", advisory.type)}
                </Typography>
                {advisory.shipmentNumber && (
                  <Typography variant="caption" color="text.secondary">{advisory.shipmentNumber}</Typography>
                )}
                {/* Importe sólo cuando lo hay. Un null significa que los dos lados no se pudieron
                    comparar, y pintar "0.00" diría que la factura coincide — lo contrario. */}
                {advisory.amount !== null && (
                  <Typography variant="caption" sx={{ fontWeight: 700 }}>
                    {advisory.currency} {advisory.amount.toFixed(2)}
                  </Typography>
                )}
              </Box>
              <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.25 }}>
                {advisory.detail}
              </Typography>
            </Paper>
          ))}
        </Box>
      )}
    </AppCard>
  );
}
