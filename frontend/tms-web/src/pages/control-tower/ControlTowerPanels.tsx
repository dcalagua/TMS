import type { ReactNode } from "react";
import { Box, Card, Chip, Paper, Typography } from "@mui/material";
import { Link } from "react-router-dom";
import {
  ReportProblemRounded, ScheduleRounded, LocalShippingRounded, BlockRounded, InfoOutlined,
  ReceiptLongRounded, WarehouseRounded, HourglassTopRounded, LinkOffRounded, PauseCircleOutlineRounded,
} from "@mui/icons-material";
import type {
  ControlTowerAdvisoryView,
  ControlTowerBlockerView, ControlTowerExceptionView, ControlTowerStopView, ControlTowerWorkloadView,
} from "../../shared/api/controlTowerApi";
import { AppCard, StatusChip } from "../../shared/ui/components";
import { R, T } from "../../theme";
import { STOP_EXECUTION_TONE, TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtMinutes, fmtPercent, fmtTime } from "../../lib/locale";
import { advisoryKey, advisoryLabel, advisoryLink, isKnownAdvisory } from "./advisories";

/** Un icono por tipo de aviso; un tipo nuevo del servidor cae al icono de información. */
const ADVISORY_ICON: Record<string, typeof InfoOutlined> = {
  SETTLEMENT_DISCREPANCY_OPEN: ReceiptLongRounded,
  STOP_ETA_MISSES_WINDOW: ScheduleRounded,
  DISPATCH_MISMATCH: WarehouseRounded,
  AWAITING_WAREHOUSE_DISPATCH: HourglassTopRounded,
  EXTERNAL_DISPATCH_UNMATCHED: LinkOffRounded,
  ORDER_HOLD_ON_COMMITTED_TRIP: PauseCircleOutlineRounded,
};

/**
 * Una banda de la franja de KPIs: una tarjeta con borde y un título en versalitas, con sus
 * métricas una al lado de otra separadas por divisores finos. Agrupa lo que en una fila de
 * nueve tarjetas iguales costaba leer como dos preguntas distintas.
 */
export function KpiBand({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card variant="outlined" component="section" aria-label={title} sx={{ borderRadius: `${R.lg}px`, boxShadow: "none", px: 2, pt: 1.5, pb: 1.75 }}>
      <Typography component="h2" sx={{
        fontSize: T.micro, fontWeight: 800, letterSpacing: ".12em", textTransform: "uppercase",
        color: "text.secondary", mb: 1.25, lineHeight: 1.4,
      }}>
        {title}
      </Typography>
      <Box sx={{
        display: "grid", rowGap: 1.5,
        gridTemplateColumns: "repeat(auto-fit, minmax(96px, 1fr))",
        "& > *": { px: 1.25 },
        "& > *:first-of-type": { pl: 0 },
        "& > *:not(:first-of-type)": { borderLeft: "1px solid", borderLeftColor: "divider" },
      }}>
        {children}
      </Box>
    </Card>
  );
}

/** Una métrica dentro de una `KpiBand`: icono y etiqueta pequeños, la cifra grande y, si la
 * hay, una línea de ayuda. Como en `KpiCard`, sólo el rojo de error colorea la cifra. */
export function BandMetric({ icon, title, value, sub, color = "primary.main" }: {
  icon: ReactNode; title: string; value: ReactNode; sub?: string; color?: string;
}) {
  return (
    <Box sx={{ minWidth: 0, display: "flex", flexDirection: "column", gap: 0.75 }}>
      <Box sx={{ display: "flex", alignItems: "flex-start", gap: 0.5, minHeight: 32 }}>
        <Box aria-hidden sx={{ color, display: "flex", mt: "1px", "& svg": { fontSize: 15 } }}>{icon}</Box>
        <Typography sx={{
          minWidth: 0, fontSize: T.label, fontWeight: 600, color: "text.secondary", lineHeight: 1.35,
          overflowWrap: "anywhere",
          display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden",
        }}>
          {title}
        </Typography>
      </Box>
      <Typography component="div" sx={{
        fontSize: T.kpiCard, fontWeight: 800, lineHeight: 1, letterSpacing: "-0.03em",
        fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap",
        color: color.startsWith("error") ? "error.main" : "text.primary",
      }}>
        {value}
      </Typography>
      {sub && (
        <Typography sx={{ fontSize: T.label, color: "text.secondary", lineHeight: 1.35 }}>{sub}</Typography>
      )}
    </Box>
  );
}

/** Un panel de la torre siempre dice de cuántos son los que enseña: "los peores veinte de
 * cuarenta y siete" es una frase distinta de "hay veinte". El contador va a la derecha de la
 * cabecera, en el hueco de acciones de la tarjeta. */
function panelHeader({ icon, label, shown, total }: { icon: React.ReactNode; label: string; shown: number; total: number }) {
  return {
    title: (
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, fontSize: T.cardTitle, fontWeight: 700 }}>
        {icon}
        {label}
      </Box>
    ),
    actions: total > shown
      ? <Chip size="small" variant="outlined" label={t("{{shown}} de {{total}}", { shown, total })} sx={{ height: 20, fontSize: 10.5, fontWeight: 700 }} />
      : undefined,
  };
}

/** La fila de lista de un panel: tarjeta con borde fino y esquinas de 10px. El borde izquierdo
 * de color, cuando lo hay, lo pone cada panel: es la mitad del mensaje. */
const ROW_SX = {
  px: 1.5, py: 1.25, borderRadius: `${R.md - 2}px`, borderColor: "divider",
  textDecoration: "none", color: "text.primary", display: "block",
  transition: "border-color .15s",
} as const;

/** La lista de filas de un panel. */
const LIST_SX = { display: "grid", gap: 1 } as const;

/**
 * Los envíos más cargados del día, con el peor de sus tres ejes de capacidad.
 *
 * El porcentaje lo decide el servidor y es *el peor* de los tres, no un promedio: lo que dice si
 * un camión está lleno es la dimensión que primero se acaba. Un `null` es "no sabemos cuánto va
 * lleno" y se dice así — pintarlo como 0% se leería como un camión vacío.
 */
export function WorkloadPanel({ items, total }: { items: ControlTowerWorkloadView[]; total: number }) {
  return (
    <AppCard {...panelHeader({ icon: <LocalShippingRounded sx={{ fontSize: 19, color: "text.disabled" }} />, label: t("Carga de los envíos"), shown: items.length, total })}>
      {items.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("No hay envíos en curso.")}</Typography>
      ) : (
        <Box sx={LIST_SX}>
          {items.map(({ trip, percentUsed }) => (
            <Paper
              key={trip.id}
              component={Link}
              to={`/trips/${trip.id}`}
              variant="outlined"
              sx={{
                ...ROW_SX, display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap",
                "&:hover": { borderColor: "primary.main" },
              }}
            >
              <Box sx={{ flex: 1, minWidth: 140 }}>
                <Typography variant="body2" sx={{ fontWeight: 700, fontSize: T.bodyStrong }}>{trip.shipmentNumber}</Typography>
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
    <AppCard {...panelHeader({ icon: <ReportProblemRounded sx={{ fontSize: 19, color: "error.main" }} />, label: t("Incidencias abiertas"), shown: items.length, total })}>
      {items.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("Ninguna incidencia abierta hoy.")}</Typography>
      ) : (
        <Box sx={LIST_SX}>
          {items.map((exception) => (
            <Paper
              key={exception.id}
              component={Link}
              to={`/trips/${exception.tripId}`}
              variant="outlined"
              sx={{
                ...ROW_SX,
                borderLeft: "3px solid", borderLeftColor: "error.main",
                "&:hover": { borderColor: "error.main" },
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                <Typography variant="body2" sx={{ fontWeight: 700, fontSize: T.bodyStrong }}>
                  {enumLabel("tripExceptionType", exception.exceptionType)}
                </Typography>
                <Typography variant="caption" color="text.secondary">{exception.shipmentNumber ?? ""}</Typography>
                <Box sx={{ flex: 1 }} />
                <Typography variant="caption" color="text.secondary">{fmtDateTime(exception.reportedAt)}</Typography>
              </Box>
              {exception.notes && (
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.5, lineHeight: 1.45 }}>
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
    <AppCard {...panelHeader({ icon: <ScheduleRounded sx={{ fontSize: 19, color: "text.disabled" }} />, label: t("Paradas pendientes"), shown: items.length, total })}>
      {items.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("No queda ninguna parada pendiente.")}</Typography>
      ) : (
        <Box sx={LIST_SX}>
          {items.map((stop) => {
            const late = stop.minutesPastWindow !== null && stop.minutesPastWindow > 0;
            return (
              <Paper
                key={stop.stopId}
                component={Link}
                to={`/trips/${stop.tripId}`}
                variant="outlined"
                sx={{
                  ...ROW_SX,
                  ...(late ? { borderLeft: "3px solid", borderLeftColor: "warning.main" } : {}),
                  "&:hover": { borderColor: "primary.main" },
                }}
              >
                <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                  <Typography variant="body2" sx={{ fontWeight: 700, fontSize: T.bodyStrong }}>
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
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.5, lineHeight: 1.45 }}>
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
    <AppCard {...panelHeader({ icon: <BlockRounded sx={{ fontSize: 19, color: "warning.main" }} />, label: t("No pueden salir"), shown: items.length, total })}>
      {items.length === 0 ? (
        // Se dice en voz alta. "No hay nada atascado" es un dato que un despachador quiere leer,
        // no deducir de un panel vacío.
        <Typography variant="body2" color="text.secondary">{t("Ningún envío bloqueado hoy.")}</Typography>
      ) : (
        <Box sx={LIST_SX}>
          {items.map((blocker) => (
            <Paper
              key={`${blocker.tripId}-${blocker.reason}`}
              component={Link}
              to={`/trips/${blocker.tripId}`}
              variant="outlined"
              sx={{
                ...ROW_SX,
                borderLeft: "3px solid", borderLeftColor: "warning.main",
                "&:hover": { borderColor: "warning.main" },
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                <Typography variant="body2" sx={{ fontWeight: 700, fontSize: T.bodyStrong }}>
                  {enumLabel("blockerReason", blocker.reason)}
                </Typography>
                <Typography variant="caption" color="text.secondary">{blocker.shipmentNumber}</Typography>
              </Box>
              <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.5, lineHeight: 1.45 }}>
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
    <AppCard {...panelHeader({ icon: <InfoOutlined sx={{ fontSize: 19, color: "info.main" }} />, label: t("Conviene saber"), shown: items.length, total })}>
      {items.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("Nada pendiente de mirar hoy.")}</Typography>
      ) : (
        <Box sx={LIST_SX}>
          {items.map((advisory, index) => {
            const link = advisoryLink(advisory);
            const Icon = ADVISORY_ICON[advisory.type] ?? InfoOutlined;
            // Un despacho sin envío asociado no tiene adónde ir: la fila se lee, no se pulsa.
            const linkProps = link === null ? { component: "div" as const } : { component: Link, to: link };
            return (
            <Paper
              key={advisoryKey(advisory, index)}
              {...linkProps}
              variant="outlined"
              sx={{
                ...ROW_SX,
                // Azul y no ámbar: el color es la mitad del mensaje de que esto no detiene nada.
                borderLeft: "3px solid", borderLeftColor: "info.main",
                "&:hover": { borderColor: "info.main" },
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                <Icon sx={{ fontSize: 17, color: "info.main" }} />
                <Typography variant="body2" sx={{ fontWeight: 700, fontSize: T.bodyStrong }}>
                  {advisoryLabel(advisory.type)}
                </Typography>
                {!isKnownAdvisory(advisory.type) && (
                  <Typography variant="caption" color="text.disabled">{advisory.type}</Typography>
                )}
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
              <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.5, lineHeight: 1.45 }}>
                {advisory.detail}
              </Typography>
            </Paper>
            );
          })}
        </Box>
      )}
    </AppCard>
  );
}
