import type { ReactNode } from "react";
import { Box, Skeleton, Typography } from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  ViewInArOutlined, Inventory2Outlined, LocalShippingOutlined, PendingActionsRounded, ScaleOutlined,
} from "@mui/icons-material";
import type { CapacityDimension, TripView } from "../../shared/api/planningApi";
import { t } from "../../lib/i18n";
import { fmtPercent, fmtQuantity, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { R, T } from "../../theme";

/** Suma una dimensión sobre los viajes. El límite solo cuenta si todos los viajes tienen uno. */
function sumDimension(trips: TripView[], pick: (trip: TripView) => CapacityDimension) {
  let used = 0;
  let limit: number | null = 0;
  for (const trip of trips) {
    const dimension = pick(trip);
    used += dimension.used;
    limit = limit === null || dimension.limit === null || dimension.unlimited ? null : limit + dimension.limit;
  }
  return { used, limit: limit && limit > 0 ? limit : null };
}

function Cell({ icon, label, value, hint, tone, loading }: {
  icon: ReactNode; label: string; value: ReactNode; hint?: string; tone?: "warning"; loading?: boolean;
}) {
  return (
    <Box sx={{
      display: "flex", alignItems: "center", gap: 1.5, px: 2, py: 1.5, minWidth: 0,
      borderRight: "1px solid", borderBottom: "1px solid", borderColor: "divider", mr: "-1px", mb: "-1px",
    }}>
      <Box aria-hidden sx={(th) => ({
        width: 32, height: 32, flexShrink: 0, borderRadius: `${R.sm}px`, display: "grid", placeItems: "center",
        bgcolor: tone ? alpha(th.palette.warning.main, th.palette.mode === "dark" ? 0.2 : 0.12) : "action.hover",
        color: tone ? "warning.main" : "text.secondary", "& svg": { fontSize: 18 },
      })}>{icon}</Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography noWrap sx={{ fontSize: T.micro, fontWeight: 700, letterSpacing: ".06em", textTransform: "uppercase", color: "text.secondary" }}>
          {label}
        </Typography>
        <Box sx={{ display: "flex", alignItems: "baseline", gap: 0.75, minWidth: 0 }}>
          <Typography component="div" sx={{ fontSize: 18, fontWeight: 800, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>
            {loading ? <Skeleton width={36} /> : value}
          </Typography>
          {hint && <Typography noWrap sx={{ fontSize: T.micro, color: "text.secondary" }}>{hint}</Typography>}
        </Box>
      </Box>
    </Box>
  );
}

/**
 * El plan de un vistazo: cuántos viajes, cuánto lleva ya y cuánto queda por asignar. Todo se
 * deriva de lo que el tablero ya tiene (los viajes con su capacidad y el total de elegibles): no
 * hay ninguna cifra que el backend no haya calculado antes.
 */
export function PlanSummaryStrip({ trips, eligibleTotal, eligibleLoading }: {
  trips: TripView[]; eligibleTotal: number | undefined; eligibleLoading: boolean;
}) {
  const live = trips.filter((trip) => trip.status !== "CANCELLED");
  const assigned = live.reduce((sum, trip) => sum + trip.orderCount, 0);
  const weight = sumDimension(live, (trip) => trip.capacity.weight);
  const volume = sumDimension(live, (trip) => trip.capacity.volume);
  const share = (used: number, limit: number, formatted: string) =>
    t("{{pct}} de {{limit}}", { pct: fmtPercent((used / limit) * 100), limit: formatted });

  return (
    <Box sx={{
      display: "grid", mb: 2, overflow: "hidden", bgcolor: "background.paper",
      border: "1px solid", borderColor: "divider", borderRadius: `${R.lg}px`,
      gridTemplateColumns: { xs: "repeat(2, minmax(0, 1fr))", md: "repeat(3, minmax(0, 1fr))", xl: "repeat(5, minmax(0, 1fr))" },
    }}>
      <Cell icon={<LocalShippingOutlined />} label={t("Viajes")} value={fmtQuantity(live.length)} />
      <Cell
        icon={<Inventory2Outlined />} label={t("Pedidos asignados")} value={fmtQuantity(assigned)}
        hint={eligibleTotal !== undefined ? t("de {{count}} liberados", { count: fmtQuantity(assigned + eligibleTotal) }) : undefined}
      />
      <Cell
        icon={<PendingActionsRounded />} label={t("Por asignar")} loading={eligibleLoading}
        value={fmtQuantity(eligibleTotal ?? 0)} hint={t("pedidos elegibles")}
        tone={eligibleTotal ? "warning" : undefined}
      />
      <Cell
        icon={<ScaleOutlined />} label={t("Peso total")} value={fmtWeightKg(weight.used)}
        hint={weight.limit ? share(weight.used, weight.limit, fmtWeightKg(weight.limit)) : undefined}
      />
      <Cell
        icon={<ViewInArOutlined />} label={t("Volumen total")} value={fmtVolumeM3(volume.used)}
        hint={volume.limit ? share(volume.used, volume.limit, fmtVolumeM3(volume.limit)) : undefined}
      />
    </Box>
  );
}
