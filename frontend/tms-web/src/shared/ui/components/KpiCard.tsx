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
 * Tarjeta KPI premium de la suite EBIM: icono en círculo con gradiente del color semántico,
 * número tabular grande a la derecha, etiqueta en versalitas. Tokens accesibles, dark-aware.
 */
export function KpiCard({ title, value, icon, color = "primary.main", sub, loading, onClick, progress, footer }: KpiCardProps) {
  const main = useColor(color);
  return (
    <Card
      variant="outlined"
      sx={{
        height: "100%", cursor: onClick ? "pointer" : "default", borderRadius: `${R.lg}px`, borderColor: "divider",
        position: "relative", overflow: "hidden",
        transition: "border-color .2s, box-shadow .2s, transform .2s",
        background: (th) => `linear-gradient(120deg, ${alpha(main, th.palette.mode === "dark" ? 0.14 : 0.07)} 0%, ${th.palette.background.paper} 60%)`,
        "&:hover": onClick ? { transform: "translateY(-3px)", boxShadow: `0 14px 30px ${alpha(main, 0.18)}`, borderColor: alpha(main, 0.4) } : {},
      }}
      onClick={onClick}
      {...(onClick ? { role: "button", tabIndex: 0, onKeyDown: (e: React.KeyboardEvent) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onClick(); } } } : {})}
    >
      <CardContent sx={{ p: { xs: 2, sm: 2.5 }, height: "100%", display: "flex", flexDirection: "column", justifyContent: "center", "&:last-child": { pb: { xs: 2, sm: 2.5 } } }}>
        {/**
         * Icono y etiqueta arriba; la cifra debajo y pegada a la izquierda.
         *
         * Antes iban los tres en una fila con la etiqueta creciendo en medio, lo que empujaba el
         * número contra el borde derecho: en una tarjeta ancha quedaba a un palmo de la etiqueta
         * que lo nombra, y había que recorrer la tarjeta entera para emparejarlos. Apilado, el
         * número queda debajo de su propio rótulo y la tarjeta se comporta igual con 240 px que
         * con 500.
         */}
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
          <Box sx={{
            width: 40, height: 40, flexShrink: 0, borderRadius: "50%", display: "grid", placeItems: "center",
            background: `linear-gradient(135deg, ${main} 0%, ${alpha(main, 0.72)} 100%)`,
            color: "#fff", boxShadow: `0 6px 16px ${alpha(main, 0.42)}`,
            "& svg": { fontSize: 20 },
          }}>{icon}</Box>
          <Typography sx={{
            flex: 1, minWidth: 0,
            textTransform: "uppercase", fontSize: T.label, fontWeight: 800, letterSpacing: ".08em",
            color: "text.secondary", lineHeight: 1.3,
            display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden",
          }}>{title}</Typography>
        </Box>

        <Typography sx={{
          mt: 1.5, fontWeight: 800, fontSize: T.kpiBig, letterSpacing: "-1px",
          lineHeight: 1, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap", color: main,
        }}>
          {loading ? <Skeleton width={64} height={30} /> : value}
        </Typography>

        {sub && (
          <Typography sx={{ mt: 0.75, fontSize: T.micro, color: "text.secondary", lineHeight: 1.3 }} noWrap>
            {sub}
          </Typography>
        )}
        {typeof progress === "number" && !loading && (
          <LinearProgress
            variant="determinate" value={Math.max(0, Math.min(100, progress))}
            sx={{ mt: 2, height: 7, borderRadius: 3, bgcolor: alpha(main, 0.16), "& .MuiLinearProgress-bar": { borderRadius: 3, bgcolor: main } }}
          />
        )}
        {footer && <Box sx={{ mt: 1 }}>{footer}</Box>}
      </CardContent>
    </Card>
  );
}
