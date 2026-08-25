import type { ReactNode } from "react";
import { Box, Card, CardContent, Divider, LinearProgress, Typography } from "@mui/material";
import { alpha } from "@mui/material/styles";
import { WarningAmberRounded, ErrorOutlineRounded } from "@mui/icons-material";
import { t } from "../../../lib/i18n";
import { fmtDecimal, fmtVolumeM3, fmtWeightKg } from "../../../lib/locale";

export interface AppCardProps {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  /** Quita el padding del cuerpo para una tarjeta cuyo contenido trae el suyo (una tabla, una lista). */
  flush?: boolean;
  /** Acento de color en el borde superior: marca la tarjeta sin gritarle al resto. */
  accent?: string;
}

/** El panel del producto. Un solo sitio es dueño de la superficie, el borde, el radio y la
 * elevación, para que los paneles no se separen pantalla a pantalla. */
export function AppCard({ title, actions, children, flush = false, accent }: AppCardProps) {
  return (
    <Card variant="outlined" sx={{ height: "100%", ...(accent ? { borderTop: "3px solid", borderTopColor: accent } : {}) }}>
      {(title || actions) && (
        <>
          <Box sx={{
            display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1.5,
            px: 2, py: 1.35,
          }}>
            <Typography component="div" variant="subtitle1" noWrap sx={{ minWidth: 0 }}>{title}</Typography>
            {actions && <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexShrink: 0 }}>{actions}</Box>}
          </Box>
          <Divider />
        </>
      )}
      {flush ? children : <CardContent sx={{ p: 2, "&:last-child": { pb: 2 } }}>{children}</CardContent>}
    </Card>
  );
}

/** Encabezado en versalitas que separa grupos de campos dentro de un formulario o panel.
 * Pinta un elemento de encabezado real para que el esquema del documento refleje la agrupación. */
export function SectionHeader({ title, actions, level = 3 }: {
  title: string;
  actions?: ReactNode;
  level?: 2 | 3 | 4;
}) {
  return (
    <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1, mb: 1.5, mt: 0.5 }}>
      <Typography
        component={`h${level}` as "h3"}
        variant="overline"
        sx={{ color: "text.secondary", lineHeight: 1.6 }}
      >
        {title}
      </Typography>
      {actions}
    </Box>
  );
}

/** Un par etiqueta/valor de solo lectura, la unidad con la que se construye un panel de detalle. */
export function DetailItem({ label, value, span }: { label: string; value: ReactNode; span?: boolean }) {
  return (
    <Box sx={{ minWidth: 0, gridColumn: span ? "1 / -1" : undefined }}>
      <Typography variant="caption" sx={{
        textTransform: "uppercase", letterSpacing: ".06em", fontWeight: 700, color: "text.secondary",
        display: "block", lineHeight: 1.6,
      }}>
        {label}
      </Typography>
      <Typography component="div" variant="body2" sx={{ fontWeight: 600, wordBreak: "break-word" }}>
        {value === null || value === undefined || value === "" ? "-" : value}
      </Typography>
    </Box>
  );
}

/** Rejilla de dos columnas para los `DetailItem`; una sola columna en móvil. */
export function DetailGrid({ children, columns = 2 }: { children: ReactNode; columns?: number }) {
  return (
    <Box sx={{
      display: "grid", gap: 1.75,
      gridTemplateColumns: { xs: "1fr", sm: `repeat(${columns}, minmax(0, 1fr))` },
    }}>
      {children}
    </Box>
  );
}

export type CapacityUnit = "weight" | "volume" | "pallets";

/** Una dimensión de capacidad tal y como la calculó el backend. */
export interface CapacityDimension {
  used: number;
  limit: number | null;
  percentUsed: number | null;
  exceeded: boolean;
  unlimited: boolean;
}

/** Una dimensión así de llena merece un aviso antes de estar realmente excedida. Esto solo
 * cambia cómo se presenta la barra, nunca el veredicto, que es del backend. */
const WARNING_THRESHOLD = 85;

const UNIT_LABEL: Record<CapacityUnit, string> = {
  weight: "Peso",
  volume: "Volumen",
  pallets: "Pallets",
};

/**
 * Una dimensión de capacidad, pintada exactamente como la calculó el backend: el componente
 * nunca deriva por su cuenta un porcentaje, un aviso ni un veredicto de exceso ("el frontend
 * nunca es de fiar", CAPACITY_MODEL).
 *
 * Cada fila muestra la etiqueta, `usado / límite` en la unidad de la dimensión y el porcentaje,
 * para que un planificador lea los números absolutos y el llenado de un vistazo en lugar de
 * inferir la carga del largo de una barra. El estado nunca se transmite solo por color: exceso
 * y casi-al-límite llevan icono y etiqueta escrita, y la barra expone su valor a la tecnología
 * asistiva.
 *
 * Tres estados que el backend documenta como genuinamente distintos se pintan distinto en vez
 * de aproximarse a una sola forma de barra:
 *
 * - `unlimited` (todavía no hay vehículo asignado): barra apagada, sin llenar y sin porcentaje;
 * - un límite real de cero (`limit === 0`, `percentUsed === null`): decir 0% o 100% serían las
 *   dos mentira, así que se dice con palabras más una línea explícita de exceso si se asignó algo;
 * - un límite normal: barra llena, coloreada por el propio `exceeded` del backend y por el
 *   umbral de casi-capacidad de arriba.
 */
export function CapacityBar({ kind, dimension }: { kind: CapacityUnit; dimension: CapacityDimension }) {
  const { used, limit, percentUsed, exceeded, unlimited } = dimension;
  const label = t(UNIT_LABEL[kind]);

  const amount = (value: number): string => {
    if (kind === "weight") return fmtWeightKg(value);
    if (kind === "volume") return fmtVolumeM3(value);
    return `${fmtDecimal(value)} ${t("pallets")}`;
  };

  const near = !exceeded && percentUsed !== null && percentUsed >= WARNING_THRESHOLD;
  const color = exceeded ? "error.main" : near ? "warning.main" : "primary.main";

  return (
    <Box sx={{ mb: 1.5 }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 1, mb: 0.5 }}>
        <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700 }}>{label}</Typography>
        <Typography
          variant="caption"
          sx={{ fontVariantNumeric: "tabular-nums", color: exceeded ? "error.main" : "text.secondary", fontWeight: exceeded ? 800 : 500 }}
        >
          {unlimited
            ? `${amount(used)} · ${t("Sin límite")}`
            : percentUsed === null
              ? `${amount(used)} / ${amount(limit ?? 0)} · ${t("Sin límite definido")}`
              : `${amount(used)} / ${amount(limit ?? 0)} · ${Math.round(percentUsed)}%`}
        </Typography>
      </Box>

      <LinearProgress
        variant="determinate"
        value={unlimited || percentUsed === null ? 0 : Math.max(0, Math.min(100, percentUsed))}
        aria-label={`${label}: ${amount(used)}`}
        sx={(th) => ({
          height: 7, borderRadius: 3,
          bgcolor: alpha(th.palette.text.primary, 0.09),
          "& .MuiLinearProgress-bar": {
            borderRadius: 3,
            // Sin vehículo asignado no hay nada que colorear: la barra queda apagada y vacía.
            bgcolor: unlimited ? alpha(th.palette.text.primary, 0.22) : color,
          },
        })}
      />

      {(exceeded || near) && (
        <Box sx={{
          display: "flex", alignItems: "center", gap: 0.5, mt: 0.5,
          color: exceeded ? "error.main" : "warning.main",
        }}>
          {exceeded ? <ErrorOutlineRounded sx={{ fontSize: 15 }} /> : <WarningAmberRounded sx={{ fontSize: 15 }} />}
          <Typography variant="caption" sx={{ fontWeight: 700 }}>
            {exceeded ? t("Capacidad excedida") : t("Cerca del límite")}
          </Typography>
        </Box>
      )}
    </Box>
  );
}
