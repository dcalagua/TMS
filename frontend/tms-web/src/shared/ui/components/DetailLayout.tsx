import type { ReactNode } from "react";
import { Box, Typography } from "@mui/material";
import { alpha } from "@mui/material/styles";
import { R, T } from "../../../theme";

/**
 * Las piezas de un panel de detalle (plantilla 4 de «Formularios y detalles v2»): los datos clave
 * en una rejilla con borde y las secciones con un título pequeño en mayúsculas. Es el mismo
 * aspecto que el panel del viaje y el de razones de elegibilidad.
 */

/** Un dato clave: etiqueta pequeña en mayúsculas y el valor en negrita. Un valor vacío es un guion. */
export interface KeyFact {
  label: string;
  value: ReactNode;
  /** Una línea de apoyo bajo el valor. */
  sub?: ReactNode;
  /** Ocupa toda la fila de la rejilla. */
  span?: boolean;
}

/** Los datos que se preguntan primero, en una rejilla con borde de 2 a 4 columnas. */
export function KeyFacts({ items, columns = 4 }: { items: KeyFact[]; columns?: 2 | 3 | 4 }) {
  return (
    <Box sx={{
      display: "grid", border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`, overflow: "hidden",
      gridTemplateColumns: {
        xs: "1fr",
        sm: `repeat(${Math.min(columns, 2)}, minmax(0, 1fr))`,
        md: `repeat(${columns}, minmax(0, 1fr))`,
      },
    }}>
      {items.map((item) => (
        <Box key={item.label} sx={{
          px: 1.5, py: 1.25, minWidth: 0, gridColumn: item.span ? "1 / -1" : undefined,
          borderRight: "1px solid", borderBottom: "1px solid", borderColor: "divider", mr: "-1px", mb: "-1px",
        }}>
          <Typography sx={{ fontSize: T.micro - 0.5, fontWeight: 700, letterSpacing: ".06em", textTransform: "uppercase", color: "text.secondary" }}>
            {item.label}
          </Typography>
          <Typography component="div" variant="body2" sx={{ fontWeight: 700, wordBreak: "break-word" }}>
            {item.value === null || item.value === undefined || item.value === "" ? "-" : item.value}
          </Typography>
          {item.sub && (
            <Typography component="div" sx={{ fontSize: T.micro, color: "text.secondary" }}>{item.sub}</Typography>
          )}
        </Box>
      ))}
    </Box>
  );
}

/** Una sección del detalle: título pequeño en mayúsculas, algo a su lado y acciones a la derecha. */
export function DetailSection({ title, extra, actions, children }: {
  title: string; extra?: ReactNode; actions?: ReactNode; children: ReactNode;
}) {
  return (
    <Box component="section" aria-label={title} sx={{ minWidth: 0 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, minHeight: 28, mb: 1, flexWrap: "wrap" }}>
        <Typography component="h3" sx={{ fontSize: T.micro, fontWeight: 800, letterSpacing: ".08em", textTransform: "uppercase", color: "text.secondary" }}>
          {title}
        </Typography>
        {extra}
        <Box sx={{ flex: 1 }} />
        {actions}
      </Box>
      {children}
    </Box>
  );
}

/**
 * El veredicto arriba del detalle: una franja de color con lo que hay que saber antes de leer el
 * resto (liberados o no, comparada o no). Mismo aspecto que el veredicto de elegibilidad.
 */
export function VerdictBanner({ tone, icon, title, message, extra }: {
  tone: "success" | "warning" | "error" | "info";
  icon?: ReactNode; title: ReactNode; message?: ReactNode;
  /** Chips junto al título. */
  extra?: ReactNode;
}) {
  return (
    <Box sx={(th) => ({
      display: "flex", gap: 1.5, alignItems: "flex-start", p: 2, borderRadius: `${R.md}px`,
      bgcolor: alpha(th.palette[tone].main, th.palette.mode === "dark" ? 0.16 : 0.08),
      boxShadow: `inset 4px 0 0 ${th.palette[tone].main}`,
    })}>
      {icon && <Box aria-hidden sx={{ color: `${tone}.main`, display: "flex", "& svg": { fontSize: 22 } }}>{icon}</Box>}
      <Box sx={{ minWidth: 0, display: "grid", gap: 0.5 }}>
        <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
          <Typography component="div" sx={{ fontWeight: 800, fontSize: T.body + 2, color: `${tone}.main` }}>{title}</Typography>
          {extra}
        </Box>
        {message && <Typography component="div" variant="body2" sx={{ color: "text.secondary" }}>{message}</Typography>}
      </Box>
    </Box>
  );
}
