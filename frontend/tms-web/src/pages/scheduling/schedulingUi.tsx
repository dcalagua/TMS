import type { ReactNode } from "react";
import { Box, ButtonBase, Skeleton, Typography } from "@mui/material";
import { alpha, type Theme } from "@mui/material/styles";
import {
  AltRouteRounded, BlockRounded, EventBusyRounded, Inventory2Outlined, LocationOffRounded,
  PanToolRounded, ScheduleRounded, WarningAmberRounded,
} from "@mui/icons-material";
import { R, T } from "../../theme";
import { StatusChip } from "../../shared/ui/components";
import type { Eligibility, SchedulingReason, SchedulingReasonCode } from "../../shared/api/schedulingApi";
import { enumLabel } from "../../lib/enums";
import { t, tp } from "../../lib/i18n";
import { ELIGIBILITY_TONE, REASON_HELP } from "./schedulingLabels";

/** Piezas de presentación compartidas por la pantalla de Programación y Liberación y sus paneles. */
export function EligibilityChip({ eligibility }: { eligibility: Eligibility }) {
  return <StatusChip label={enumLabel("eligibility", eligibility)} tone={ELIGIBILITY_TONE[eligibility]} />;
}

/** El color de paleta de cada elegibilidad y de cada severidad de motivo. */
export const ELIGIBILITY_COLOR: Record<Eligibility, "success" | "warning" | "error"> = {
  ELIGIBLE: "success", WARNING: "warning", BLOCKED: "error",
};

const REASON_ICON: Record<SchedulingReasonCode, ReactNode> = {
  ACTIVE_BLOCKING_HOLD: <PanToolRounded />,
  CUTOFF_MISSED: <ScheduleRounded />,
  FREQUENCY_OVERRIDE: <EventBusyRounded />,
  MISSING_CAPACITY: <Inventory2Outlined />,
  MISSING_DESTINATION: <LocationOffRounded />,
  MISSING_ORIGIN: <LocationOffRounded />,
  ROUTE_AMBIGUOUS: <AltRouteRounded />,
  ROUTE_NOT_CONFIGURED: <AltRouteRounded />,
  ROUTE_NOT_FOUND: <AltRouteRounded />,
};

/** La lista de motivos de una fila: una tarjeta por motivo, con qué significa y el texto del backend. */
export function ReasonList({ reasons }: { reasons: SchedulingReason[] }) {
  if (reasons.length === 0) {
    return <Typography variant="body2" color="text.secondary">{t("Sin observaciones: el pedido es elegible.")}</Typography>;
  }
  return (
    <Box component="ul" sx={{ listStyle: "none", p: 0, m: 0, display: "grid", gap: 1 }}>
      {reasons.map((reason) => {
        const color = ELIGIBILITY_COLOR[reason.severity];
        return (
          <Box component="li" key={reason.code} sx={{
            display: "flex", gap: 1.5, alignItems: "flex-start", p: 1.5,
            border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`,
          }}>
            <Box aria-hidden sx={(th) => ({
              width: 30, height: 30, flexShrink: 0, borderRadius: `${R.sm}px`, display: "grid", placeItems: "center",
              bgcolor: alpha(th.palette[color].main, th.palette.mode === "dark" ? 0.2 : 0.12), color: `${color}.main`,
              "& svg": { fontSize: 17 },
            })}>
              {REASON_ICON[reason.code]}
            </Box>
            <Box sx={{ minWidth: 0, display: "grid", gap: 0.25 }}>
              <Box sx={{ display: "flex", gap: 1, alignItems: "baseline", flexWrap: "wrap" }}>
                <Typography sx={{ fontWeight: 700, fontSize: T.body }}>
                  {enumLabel("schedulingReason", reason.code)}
                </Typography>
                {reason.requiresOverride && (
                  <Typography sx={{ fontSize: T.micro, color: "warning.main", fontWeight: 700 }}>
                    {t("Requiere motivo para liberar")}
                  </Typography>
                )}
              </Box>
              <Typography variant="body2">{t(REASON_HELP[reason.code])}</Typography>
              {reason.detail && (
                <Typography sx={{ fontSize: T.micro, color: "text.secondary" }}>{reason.detail}</Typography>
              )}
            </Box>
          </Box>
        );
      })}
    </Box>
  );
}

type KpiColor = "info" | "success" | "warning" | "error" | "secondary";

export interface KpiStripItem {
  key: string;
  icon: ReactNode;
  color: KpiColor;
  title: string;
  value: ReactNode;
  hint?: string;
  active: boolean;
  onClick: () => void;
}

/**
 * Los indicadores de la pantalla, que además son filtros, en una sola franja: cada tramo es un
 * botón, y el del filtro aplicado se marca con fondo tenue y una línea inferior de su color para
 * que se vea qué está recortando la lista.
 */
export function KpiStrip({ items, loading }: { items: KpiStripItem[]; loading?: boolean }) {
  return (
    <Box sx={{
      display: "grid", mb: 2, overflow: "hidden", bgcolor: "background.paper",
      border: "1px solid", borderColor: "divider", borderRadius: `${R.lg}px`,
      gridTemplateColumns: { xs: "repeat(2, minmax(0, 1fr))", sm: "repeat(3, minmax(0, 1fr))", md: `repeat(${items.length}, minmax(0, 1fr))` },
    }}>
      {items.map((item) => (
        <ButtonBase
          key={item.key}
          onClick={item.onClick}
          aria-pressed={item.active}
          sx={(th: Theme) => ({
            justifyContent: "flex-start", gap: 1.5, px: 2, py: 1.5, textAlign: "left",
            borderRight: "1px solid", borderBottom: "1px solid", borderColor: "divider", mr: "-1px", mb: "-1px",
            bgcolor: item.active ? alpha(th.palette[item.color].main, th.palette.mode === "dark" ? 0.14 : 0.06) : "transparent",
            boxShadow: item.active ? `inset 0 -3px 0 ${th.palette[item.color].main}` : "none",
            transition: "background-color .15s",
            "&:hover": { bgcolor: alpha(th.palette[item.color].main, th.palette.mode === "dark" ? 0.18 : 0.08) },
            "&.Mui-focusVisible": { outline: "2px solid", outlineColor: th.palette.primary.main, outlineOffset: "-2px" },
          })}
        >
          <Box aria-hidden sx={(th) => ({
            width: 36, height: 36, flexShrink: 0, borderRadius: `${R.sm}px`, display: "grid", placeItems: "center",
            bgcolor: alpha(th.palette[item.color].main, th.palette.mode === "dark" ? 0.2 : 0.12), color: `${item.color}.main`,
            "& svg": { fontSize: 19 },
          })}>{item.icon}</Box>
          <Box sx={{ minWidth: 0 }}>
            <Typography noWrap sx={{
              fontSize: T.micro, fontWeight: 700, letterSpacing: ".08em", textTransform: "uppercase",
              color: "text.secondary", lineHeight: 1.4,
            }}>
              {item.title}
            </Typography>
            <Box sx={{ display: "flex", alignItems: "baseline", gap: 1, minWidth: 0 }}>
              <Typography component="div" sx={{
                fontSize: T.kpiCard, fontWeight: 800, letterSpacing: "-0.03em", lineHeight: 1.1,
                fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap",
              }}>
                {loading ? <Skeleton width={40} height={26} /> : item.value}
              </Typography>
              {item.hint && (
                <Typography noWrap sx={{ fontSize: T.micro, color: "text.secondary", display: { xs: "none", lg: "block" } }}>
                  {item.hint}
                </Typography>
              )}
            </Box>
          </Box>
        </ButtonBase>
      ))}
    </Box>
  );
}

/** Veredicto del panel de razones: qué significa la elegibilidad para quien tiene que actuar. */
export function EligibilityVerdict({ eligibility, blockingCount, warningCount, status }: {
  eligibility: Eligibility; blockingCount: number; warningCount: number; status: ReactNode;
}) {
  const color = ELIGIBILITY_COLOR[eligibility];
  const icon = eligibility === "BLOCKED" ? <BlockRounded /> : eligibility === "WARNING" ? <WarningAmberRounded /> : null;
  const message = eligibility === "BLOCKED"
    ? tp(blockingCount, "No puede pasar a planificación mientras tenga {{count}} motivo bloqueante.", "No puede pasar a planificación mientras tenga {{count}} motivos bloqueantes.")
    : eligibility === "WARNING"
      ? tp(warningCount, "Se puede liberar indicando un motivo ({{count}} aviso).", "Se puede liberar indicando un motivo ({{count}} avisos).")
      : t("Sin observaciones: se puede liberar.");
  return (
    <Box sx={(th) => ({
      display: "flex", gap: 1.5, alignItems: "flex-start", p: 2, borderRadius: `${R.md}px`,
      bgcolor: alpha(th.palette[color].main, th.palette.mode === "dark" ? 0.16 : 0.08),
      boxShadow: `inset 4px 0 0 ${th.palette[color].main}`,
    })}>
      {icon && <Box aria-hidden sx={{ color: `${color}.main`, display: "flex", "& svg": { fontSize: 22 } }}>{icon}</Box>}
      <Box sx={{ minWidth: 0, display: "grid", gap: 0.5 }}>
        <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
          <Typography sx={{ fontWeight: 800, fontSize: T.body + 2, color: `${color}.main` }}>
            {enumLabel("eligibility", eligibility)}
          </Typography>
          {status}
        </Box>
        <Typography variant="body2" sx={{ color: "text.secondary" }}>{message}</Typography>
      </Box>
    </Box>
  );
}
