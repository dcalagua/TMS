import type { ReactNode } from "react";
import { Box, Card, Typography } from "@mui/material";
import { R } from "../../theme";

export interface WorkspaceCardProps {
  title?: ReactNode;
  /** Icono pequeño delante del título; decorativo. */
  icon?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  /** Quita el padding del cuerpo para un contenido que trae el suyo. */
  flush?: boolean;
}

/**
 * La tarjeta del espacio de trabajo de un envío: borde fino, radio de 14px, 16px de aire y el
 * título en 15/700 con su icono, sin la franja divisoria del `AppCard` de las listas. Vive aquí
 * y no en `shared/` porque es la densidad de esta pantalla, donde una docena de paneles se leen
 * de corrido y una raya bajo cada título los partía en el doble de bloques.
 */
export function WorkspaceCard({ title, icon, actions, children, flush = false }: WorkspaceCardProps) {
  return (
    <Card variant="outlined" sx={{ borderRadius: `${R.lg}px`, p: flush ? 0 : 2 }}>
      {(title || actions) && (
        <Box sx={{
          display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1.5, flexWrap: "wrap",
          mb: 1.5, ...(flush ? { px: 2, pt: 2 } : {}),
        }}>
          <Typography
            component="h2"
            sx={{
              display: "flex", alignItems: "center", gap: 1, minWidth: 0, whiteSpace: "nowrap",
              fontSize: 15, fontWeight: 700, lineHeight: 1.35,
              "& > svg": { fontSize: 18, color: "text.secondary", flexShrink: 0 },
            }}
          >
            {icon}
            <Box component="span" sx={{ minWidth: 0, display: "inline-flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
              {title}
            </Box>
          </Typography>
          {actions && <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap", justifyContent: "flex-end", ml: "auto" }}>{actions}</Box>}
        </Box>
      )}
      {children}
    </Card>
  );
}
