import type { ReactNode } from "react";
import { Box, Button, Typography } from "@mui/material";
import { RefreshRounded } from "@mui/icons-material";
import { TableSearch } from "./TableSearch";
import { t } from "../../../lib/i18n";

interface PageHeaderProps {
  /** Título de la página (h5). */
  title: string;
  /** Subtítulo opcional bajo el título. */
  subtitle?: string;
  /** Icono a la izquierda del título; se pinta dentro de la baldosa de identidad del módulo. */
  icon?: ReactNode;
  /**
   * @deprecated Diseño v2: todas las baldosas usan el primario del tema, para que la cabecera
   * se lea igual en todo el producto. Se conserva para no romper las llamadas existentes.
   */
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
  title, subtitle, icon, meta, search, onRefresh, refreshing, actions,
}: PageHeaderProps) {
  const tint = "primary.main";
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
              // Baldosa plana del diseño v2: color sólido, sin degradado ni sombra.
              return {
                width: 44, height: 44, flexShrink: 0, borderRadius: "12px",
                display: "grid", placeItems: "center",
                bgcolor: main, color: "#fff",
                "& svg": { fontSize: 22 },
              };
            }}
          >
            {icon}
          </Box>
        )}
        <Box sx={{ minWidth: 0 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            <Typography variant="h5" noWrap sx={{ fontSize: 21, fontWeight: 800, letterSpacing: "-0.02em" }}>{title}</Typography>
            {meta}
          </Box>
          {subtitle && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>{subtitle}</Typography>
          )}
        </Box>
      </Box>

      <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexShrink: 0 }}>
        {search && (
          <TableSearch value={search.value} onChange={search.onChange} placeholder={search.placeholder} />
        )}
        {onRefresh && (
          <Button
            variant="outlined"
            color="inherit"
            onClick={onRefresh}
            disabled={refreshing}
            startIcon={<RefreshRounded />}
            sx={{ borderColor: "divider", whiteSpace: "nowrap" }}
          >
            {t("Recargar")}
          </Button>
        )}
        {actions}
      </Box>
    </Box>
  );
}
