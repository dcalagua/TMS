import type { ReactNode } from "react";
import { Box, Card, CardContent, Typography, Skeleton, LinearProgress } from "@mui/material";
import { useTheme, alpha } from "@mui/material/styles";
import { R, T } from "../../../theme";

interface KpiCardProps {
  title: string;
  value: ReactNode;
  icon: ReactNode;
  /** Color del icono/acento: token del theme ("info.main", "success.main"…) o hex. Default primary. */
  color?: string;
  sub?: string;
  loading?: boolean;
  onClick?: () => void;
  /** Barra de progreso 0-100 (ej. uso de capacidad, cumplimiento de entregas). */
  progress?: number;
  /** Contenido extra al pie (chip de estado, delta…). */
  footer?: ReactNode;
}

// Resuelve "info.main"/"success.main"/… o un hex a un color real del theme.
function useColor(c: string): string {
  const theme = useTheme();
  if (c.startsWith("#")) return c;
  const [k, sub = "main"] = c.split(".");
  const palette = theme.palette as unknown as Record<string, Record<string, string>>;
  return palette[k]?.[sub] ?? theme.palette.primary.main;
}

/**
 * Tarjeta KPI de la suite EBIM (diseño v2, `design/tms.pen`).
 *
 * Plana: borde fino, sin degradado ni sombra. Arriba una baldosa suave con el icono en el color
 * semántico y la etiqueta; debajo la cifra en tinta normal, tabular y grande. El color vive en la
 * baldosa y no en el número: una fila de seis cifras de seis colores no deja ver cuál importa.
 * Solo el rojo de error se queda en la cifra, porque ese sí tiene que saltar a la vista.
 */
export function KpiCard({ title, value, icon, color = "primary.main", sub, loading, onClick, progress, footer }: KpiCardProps) {
  const main = useColor(color);
  const isError = color.startsWith("error");
  return (
    <Card
      variant="outlined"
      sx={{
        height: "100%", cursor: onClick ? "pointer" : "default", borderRadius: `${R.lg}px`, borderColor: "divider",
        boxShadow: "none", backgroundImage: "none",
        transition: "border-color .15s",
        "&:hover": onClick ? { borderColor: alpha(main, 0.6) } : { borderColor: "divider" },
      }}
      onClick={onClick}
      {...(onClick ? { role: "button", tabIndex: 0, onKeyDown: (e: React.KeyboardEvent) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onClick(); } } } : {})}
    >
      <CardContent sx={{ p: 2, height: "100%", display: "flex", flexDirection: "column", gap: 1.25, "&:last-child": { pb: 2 } }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.25 }}>
          <Box aria-hidden sx={(th) => ({
            width: 30, height: 30, flexShrink: 0, borderRadius: "8px", display: "grid", placeItems: "center",
            bgcolor: alpha(main, th.palette.mode === "dark" ? 0.2 : 0.12), color: main,
            "& svg": { fontSize: 17 },
          })}>{icon}</Box>
          <Typography sx={{
            flex: 1, minWidth: 0, fontSize: T.body - 0.5, fontWeight: 700, color: "text.secondary", lineHeight: 1.3,
            display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden",
          }}>{title}</Typography>
        </Box>

        <Typography component="div" sx={{
          fontWeight: 800, fontSize: T.kpiCard + 2, letterSpacing: "-0.03em",
          lineHeight: 1, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap",
          color: isError ? "error.dark" : "text.primary",
        }}>
          {loading ? <Skeleton width={64} height={30} /> : value}
        </Typography>

        {typeof progress === "number" && !loading && (
          <LinearProgress
            variant="determinate" value={Math.max(0, Math.min(100, progress))}
            sx={{ height: 6, borderRadius: "3px", bgcolor: alpha(main, 0.14), "& .MuiLinearProgress-bar": { borderRadius: "3px", bgcolor: main } }}
          />
        )}
        {sub && (
          <Typography sx={{ fontSize: T.label + 0.5, color: "text.secondary", lineHeight: 1.35 }} noWrap>
            {sub}
          </Typography>
        )}
        {footer && <Box>{footer}</Box>}
      </CardContent>
    </Card>
  );
}
