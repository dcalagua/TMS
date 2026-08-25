import type { ReactNode } from "react";
import { Box, Typography, IconButton, Tooltip } from "@mui/material";
import { RefreshRounded } from "@mui/icons-material";
import { alpha } from "@mui/material/styles";
import { TableSearch } from "./TableSearch";
import { t } from "../../../lib/i18n";

interface PageHeaderProps {
  /** Título de la página (h5). */
  title: string;
  /** Subtítulo opcional bajo el título. */
  subtitle?: string;
  /** Icono a la izquierda del título; se pinta dentro de la baldosa de identidad del módulo. */
  icon?: ReactNode;
  /** Color de la baldosa. Token del theme o hex; por defecto el primario. */
  tint?: string;
  /** Datos cortos junto al título — un conteo, una fecha, un estado. */
  meta?: ReactNode;
  /** Si se pasa, muestra el buscador de tabla. */
  search?: { value: string; onChange: (v: string) => void; placeholder?: string };
  /** Si se pasa, muestra el botón de recargar. */
  onRefresh?: () => void;
  refreshing?: boolean;
  /** Acciones a la derecha (ej. botón "Nuevo …"). */
  actions?: ReactNode;
}

/**
 * Cabecera de página estándar de la suite EBIM: baldosa de icono + título (+subtítulo) a la
 * izquierda; buscador, recargar y acciones a la derecha.
 *
 * La baldosa es decorativa y va marcada `aria-hidden`: el encabezado ya nombra la página, y
 * anunciar un icono solo añadiría ruido. Lo que aporta es un ancla visual fija arriba a la
 * izquierda de cada pantalla, que es lo que hace que ocho listas distintas se sientan un solo
 * producto.
 */
export function PageHeader({
  title, subtitle, icon, tint = "primary.main", meta, search, onRefresh, refreshing, actions,
}: PageHeaderProps) {
  return (
    <Box
      sx={{
        display: "flex", alignItems: { xs: "stretch", sm: "center" },
        justifyContent: "space-between", flexDirection: { xs: "column", sm: "row" },
        gap: 1.5, mb: 3,
      }}
    >
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.75, minWidth: 0 }}>
        {icon && (
          <Box
            aria-hidden
            sx={(th) => {
              const [k, sub = "main"] = tint.split(".");
              const palette = th.palette as unknown as Record<string, Record<string, string>>;
              const main = tint.startsWith("#") ? tint : (palette[k]?.[sub] ?? th.palette.primary.main);
              return {
                width: 44, height: 44, flexShrink: 0, borderRadius: 2.5,
                display: "grid", placeItems: "center",
                background: `linear-gradient(135deg, ${main} 0%, ${alpha(main, 0.72)} 100%)`,
                color: "#fff", boxShadow: `0 5px 14px ${alpha(main, 0.38)}`,
                "& svg": { fontSize: 23 },
              };
            }}
          >
            {icon}
          </Box>
        )}
        <Box sx={{ minWidth: 0 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            <Typography variant="h5" noWrap>{title}</Typography>
            {meta}
          </Box>
          {subtitle && (
            <Typography variant="body2" color="text.secondary" noWrap>{subtitle}</Typography>
          )}
        </Box>
      </Box>

      <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexShrink: 0 }}>
        {search && (
          <TableSearch value={search.value} onChange={search.onChange} placeholder={search.placeholder} />
        )}
        {onRefresh && (
          <Tooltip title={t("Recargar")}>
            <span>
              <IconButton onClick={onRefresh} disabled={refreshing} aria-label={t("Recargar")}>
                <RefreshRounded />
              </IconButton>
            </span>
          </Tooltip>
        )}
        {actions}
      </Box>
    </Box>
  );
}
