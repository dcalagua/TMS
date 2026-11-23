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
  /**
   * Alto máximo del cuerpo, en px: pasado ese alto se desplaza y el título queda a la vista. Para
   * las listas que crecen con el viaje (las paradas de un reparto de ocho locales).
   */
  maxHeight?: number;
  /**
   * Alto máximo de la tarjeta entera, título incluido, en px. A diferencia de `maxHeight`, no se
   * desplaza todo el cuerpo: el título y los totales conservan su alto y lo que se desplaza es el
   * listado, envuelto en `WorkspaceCardScroll`.
   */
  cardMaxHeight?: number;
}

/**
 * La tarjeta del espacio de trabajo de un envío: borde fino, radio de 14px, 16px de aire y el
 * título en 15/700 con su icono, sin la franja divisoria del `AppCard` de las listas. Vive aquí
 * y no en `shared/` porque es la densidad de esta pantalla, donde una docena de paneles se leen
 * de corrido y una raya bajo cada título los partía en el doble de bloques.
 */
export function WorkspaceCard({ title, icon, actions, children, flush = false, maxHeight, cardMaxHeight }: WorkspaceCardProps) {
  return (
    <Card variant="outlined" sx={{
      borderRadius: `${R.lg}px`, p: flush ? 0 : 2,
      // Una columna flexible: cada bloque guarda su alto natural y solo el listado, que es
      // contenedor de desplazamiento, cede lo que sobra del alto máximo.
      ...(cardMaxHeight === undefined ? {} : { maxHeight: cardMaxHeight, display: "flex", flexDirection: "column" }),
    }}>
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
      {maxHeight === undefined ? children : (
        // `tabIndex`: un contenedor que solo se desplaza con la rueda deja fuera a quien usa teclado.
        <Box tabIndex={0} sx={{ maxHeight, overflowY: "auto", pr: 0.5, mr: -0.5 }}>{children}</Box>
      )}
    </Card>
  );
}

/**
 * El listado de una tarjeta con `cardMaxHeight`: es lo único que se desplaza cuando la tarjeta no
 * cabe. `tabIndex` por lo mismo que en `maxHeight`: quien usa teclado también tiene que llegar.
 */
export function WorkspaceCardScroll({ children }: { children: ReactNode }) {
  return <Box tabIndex={0} sx={{ minHeight: 0, overflowY: "auto", pr: 0.5, mr: -0.5 }}>{children}</Box>;
}
