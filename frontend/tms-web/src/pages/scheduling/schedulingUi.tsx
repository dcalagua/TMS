import type { KeyboardEvent, ReactNode } from "react";
import { Box, Card, Skeleton, Typography } from "@mui/material";
import { alpha } from "@mui/material/styles";
import { R, T } from "../../theme";
import { StatusChip } from "../../shared/ui/components";
import type { Eligibility, SchedulingReason } from "../../shared/api/schedulingApi";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { ELIGIBILITY_TONE, REASON_HELP, reasonTone } from "./schedulingLabels";

/** Piezas de presentación compartidas por la pantalla de Programación y Liberación y sus paneles. */
export function EligibilityChip({ eligibility }: { eligibility: Eligibility }) {
  return <StatusChip label={enumLabel("eligibility", eligibility)} tone={ELIGIBILITY_TONE[eligibility]} />;
}

/** La lista de motivos de una fila, con qué significa cada uno y el texto del backend debajo. */
export function ReasonList({ reasons }: { reasons: SchedulingReason[] }) {
  if (reasons.length === 0) {
    return <Typography variant="body2" color="text.secondary">{t("Sin observaciones: el pedido es elegible.")}</Typography>;
  }
  return (
    <Box component="ul" sx={{ listStyle: "none", p: 0, m: 0, display: "grid", gap: 1.25 }}>
      {reasons.map((reason) => (
        <Box component="li" key={reason.code} sx={{ display: "grid", gap: 0.5 }}>
          <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
            <StatusChip label={enumLabel("schedulingReason", reason.code)} tone={reasonTone(reason)} />
            {reason.requiresOverride && (
              <Typography variant="caption" color="text.secondary">{t("Requiere motivo para liberar")}</Typography>
            )}
          </Box>
          <Typography variant="body2">{t(REASON_HELP[reason.code])}</Typography>
          <Typography variant="caption" color="text.secondary">{reason.detail}</Typography>
        </Box>
      ))}
    </Box>
  );
}

type KpiColor = "info" | "success" | "warning" | "error" | "secondary";

/**
 * KPI compacto de la pantalla, que además es un filtro: baldosa con el icono a la izquierda,
 * etiqueta en versalitas y cifra a la derecha. La tarjeta del filtro aplicado lleva un borde
 * primario de 2px para que se vea qué está recortando la lista.
 */
export function FilterKpiCard({ icon, color, title, value, loading, active, onClick }: {
  icon: ReactNode;
  color: KpiColor;
  title: string;
  value: ReactNode;
  loading?: boolean;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <Card
      variant="outlined"
      role="button"
      tabIndex={0}
      aria-pressed={active}
      onClick={onClick}
      onKeyDown={(e: KeyboardEvent) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onClick(); } }}
      sx={(th) => ({
        height: "100%", cursor: "pointer", borderRadius: `${R.lg}px`, boxShadow: "none", backgroundImage: "none",
        display: "flex", alignItems: "center", gap: 1.5, px: 2, py: 1.5,
        // El borde de 2px se compensa con el padding para que la tarjeta activa no salte de tamaño.
        border: active ? `2px solid ${th.palette.primary.main}` : `1px solid ${th.palette.divider}`,
        ...(active ? { px: "15px", py: "11px" } : {}),
        transition: "border-color .15s",
        "&:hover": { borderColor: active ? th.palette.primary.main : alpha(th.palette[color].main, 0.6) },
        "&:focus-visible": { outline: "2px solid", outlineColor: th.palette.primary.main, outlineOffset: "2px" },
      })}
    >
      <Box aria-hidden sx={(th) => ({
        width: 36, height: 36, flexShrink: 0, borderRadius: `${R.sm}px`, display: "grid", placeItems: "center",
        bgcolor: alpha(th.palette[color].main, th.palette.mode === "dark" ? 0.2 : 0.12), color: `${color}.main`,
        "& svg": { fontSize: 19 },
      })}>{icon}</Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography noWrap sx={{
          fontSize: T.micro, fontWeight: 700, letterSpacing: ".08em", textTransform: "uppercase",
          color: "text.secondary", lineHeight: 1.4,
        }}>
          {title}
        </Typography>
        <Typography component="div" sx={{
          fontSize: T.kpiCard, fontWeight: 800, letterSpacing: "-0.03em", lineHeight: 1.1,
          fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap",
        }}>
          {loading ? <Skeleton width={48} height={26} /> : value}
        </Typography>
      </Box>
    </Card>
  );
}
