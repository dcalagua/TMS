import { Box, Chip, Tooltip, Typography } from "@mui/material";
import { CropSquareOutlined, FilterNoneOutlined, LayersOutlined, WarningAmberRounded } from "@mui/icons-material";
import type { StackMode } from "../api/materialProfilesApi";
import { enumLabel } from "../../lib/enums";
import { t, tp } from "../../lib/i18n";
import { T } from "../../theme";
import {
  occupancyText, orderGeometryState, volumeComparisonText, volumeSavingText,
} from "./loadGeometry";

const STACK_MODE_ICON: Record<StackMode, typeof LayersOutlined> = {
  STACKABLE: LayersOutlined,
  NESTABLE: FilterNoneOutlined,
  NONE: CropSquareOutlined,
};

/**
 * Cómo se apila una línea: icono y etiqueta (nunca solo el icono). Null = el bloque físico no lo
 * declara, que no es lo mismo que "no se apila".
 */
export function StackModeChip({ mode }: { mode: StackMode | null | undefined }) {
  const Icon = mode ? STACK_MODE_ICON[mode] : null;
  return (
    <Chip
      size="small" variant="outlined"
      icon={Icon ? <Icon sx={{ fontSize: 15 }} /> : undefined}
      label={mode ? enumLabel("stackMode", mode) : t("No declarado")}
      sx={{ height: 22, fontWeight: 700, fontSize: T.micro + 0.5, color: mode ? "text.primary" : "text.secondary" }}
    />
  );
}

/** Lo que el backend devuelve de la geometría de un pedido, en la forma de la lista o del detalle. */
export interface OrderOccupancy {
  planningVolumeM3?: number | null;
  floorAreaM2?: number | null;
  maxStackHeightM?: number | null;
  stacks?: number | null;
  unmeasuredLines?: number | null;
}

/**
 * La celda «Ocupa» de un pedido: pilas, piso y alto arriba; volumen efectivo frente al declarado
 * debajo. Sin geometría lo dice ("por volumen declarado") en vez de dejar un hueco que se lea como
 * "no ocupa nada".
 *
 * `declaredVolumeM3` es el total del pedido (`totalVolumeM3`): la cifra ingenua que se planificaba
 * antes de V62 y que sigue siendo la de auditoría.
 */
export function OccupancyCell({ geometry, declaredVolumeM3, dense = false }: {
  geometry: OrderOccupancy | null | undefined;
  declaredVolumeM3: number | null | undefined;
  /** Una sola línea, para el panel de pedidos elegibles. */
  dense?: boolean;
}) {
  const state = orderGeometryState(geometry);
  const unmeasured = geometry?.unmeasuredLines ?? 0;
  const secondarySx = { fontSize: T.micro + 0.5, color: "text.secondary", fontVariantNumeric: "tabular-nums" } as const;

  if (state === "none" || !geometry) {
    return <Typography noWrap sx={secondarySx}>{t("Por volumen declarado")}</Typography>;
  }

  const figures = occupancyText({
    stacks: geometry.stacks, floorAreaM2: geometry.floorAreaM2, stackHeightM: geometry.maxStackHeightM,
  });
  const volumes = volumeComparisonText(geometry.planningVolumeM3, declaredVolumeM3);
  const saving = volumeSavingText(geometry.planningVolumeM3, declaredVolumeM3);
  const unmeasuredNote = unmeasured > 0
    ? tp(unmeasured, "{{count}} línea sin medir", "{{count}} líneas sin medir")
    : null;

  if (state === "unmeasured") {
    return (
      <Box sx={{ minWidth: 0 }}>
        <Typography noWrap sx={{ fontSize: T.body - 0.5, fontWeight: 700, color: "warning.main", display: "flex", alignItems: "center", gap: 0.5 }}>
          <WarningAmberRounded aria-hidden sx={{ fontSize: 15 }} />
          {unmeasuredNote}
        </Typography>
        {!dense && <Typography noWrap sx={secondarySx}>{t("Por volumen declarado")}</Typography>}
      </Box>
    );
  }

  if (dense) {
    return (
      <Tooltip title={saving ?? ""} disableHoverListener={!saving}>
        <Typography noWrap sx={secondarySx}>
          {t("Ocupa")}: {[figures, volumes].filter(Boolean).join(" · ")}
          {unmeasuredNote && (
            <Box component="span" sx={{ color: "warning.main", fontWeight: 700 }}> · {unmeasuredNote}</Box>
          )}
        </Typography>
      </Tooltip>
    );
  }

  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography noWrap sx={{ fontSize: T.body - 0.5, fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
        {figures || volumes}
      </Typography>
      <Tooltip title={saving ?? ""} disableHoverListener={!saving}>
        <Typography noWrap sx={secondarySx}>
          {volumes}
          {unmeasuredNote && (
            <Box component="span" sx={{ color: "warning.main", fontWeight: 700 }}> · {unmeasuredNote}</Box>
          )}
        </Typography>
      </Tooltip>
    </Box>
  );
}
