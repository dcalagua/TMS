import type { ReactNode } from "react";
import { Box, Drawer, IconButton, Typography, Divider } from "@mui/material";
import { CloseRounded } from "@mui/icons-material";
import { LoadingState } from "./states";
import { confirmDialog } from "../../../lib/ui";
import { t } from "../../../lib/i18n";

export type DrawerSize = "sm" | "md" | "lg" | "xl";

const WIDTH: Record<DrawerSize, number> = { sm: 420, md: 560, lg: 760, xl: 980 };

export interface FormDrawerProps {
  open: boolean;
  /** Nombra el panel; también es el destino de su `aria-labelledby`. */
  title: string;
  /** Una línea de contexto bajo el título. */
  subtitle?: string;
  onClose: () => void;
  children: ReactNode;
  /** Fila de acciones. Convención: cancelar primero como botón secundario, la acción principal al final. */
  footer?: ReactNode;
  size?: DrawerSize;
  /** Sustituye el cuerpo por un indicador de carga — para un panel de detalle que aún está pidiendo datos. */
  loading?: boolean;
  closeOnBackdrop?: boolean;
  /**
   * `true` mientras el formulario de dentro tiene ediciones sin guardar. Cerrar por la X, por
   * Escape o por el fondo pide entonces confirmación; una acción explícita del pie es asunto de
   * quien llama y nunca pasa por aquí.
   */
  dirty?: boolean;
  /** Icono de identidad a la izquierda del título. */
  icon?: ReactNode;
}

/**
 * La superficie de crear / editar / ver detalle / configurar del producto.
 *
 * eTMS usa un panel lateral derecho en vez de un modal centrado para esto: la lista, el tablero
 * o el proceso del que venía el operador sigue visible detrás, así que editar un origen no
 * sustituye la pantalla de orígenes que estaba leyendo. Esta es la única superficie de diálogo
 * del producto para formularios — las confirmaciones y las acciones destructivas van al
 * `confirmDialog` de `lib/ui`, y cualquier cosa mayor se gana su propia página.
 *
 * El atrapado de foco, el foco inicial, Escape, el bloqueo de scroll y la restauración de foco
 * los aporta el `Drawer` modal de MUI, así que ese comportamiento no se reescribe aquí.
 */
export function FormDrawer({
  open, title, subtitle, onClose, children, footer, size = "md",
  loading = false, closeOnBackdrop = true, dirty = false, icon,
}: FormDrawerProps) {
  /**
   * El camino de descarte por el que pasa todo cierre "blando". El trabajo sin guardar nunca se
   * tira en silencio; un formulario limpio cierra al momento, porque una confirmación que nadie
   * necesita es solo fricción.
   */
  const requestClose = () => {
    if (!dirty) { onClose(); return; }
    void confirmDialog({
      title: t("¿Descartar cambios?"),
      text: t("Tienes cambios sin guardar."),
      confirmLabel: t("Descartar"),
      cancelLabel: t("Seguir editando"),
      dangerous: true,
    }).then((confirmed) => { if (confirmed) onClose(); });
  };

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={(_e, reason) => {
        if (reason === "backdropClick" && !closeOnBackdrop) return;
        requestClose();
      }}
      /**
       * Por encima de la barra superior.
       *
       * El `Drawer` de MUI se apila en `zIndex.drawer` (1200) y la barra del armazón está un
       * punto más arriba, en `drawer + 1`, para poder montarse sobre la lateral fija. Con el
       * valor de fábrica, la barra se pintaba sobre este panel y se comía sus primeros sesenta
       * píxeles: desaparecían el título, el subtítulo y la X de cerrar, y el formulario parecía
       * empezar directamente por el primer campo.
       *
       * Peor que lo estético: el panel es modal —atrapa el foco—, así que la barra quedaba
       * visible y sin atenuar por encima de un fondo oscurecido, ofreciendo unos controles que
       * ya no se podían usar. Un panel modal va sobre todo, y su fondo también.
       *
       * <p>Se cuenta desde `zIndex.drawer` y NO desde `zIndex.appBar`, que en MUI vale 1100 —cien
       * por debajo del cajón—. La barra del armazón no usa ese valor: se sube a mano a
       * `drawer + 1`. Partir del token daría 1101 y dejaría el panel aún más abajo que antes.
       */
      sx={{ zIndex: (theme) => theme.zIndex.drawer + 2 }}
      slotProps={{
        paper: {
          sx: {
            width: { xs: "100%", sm: WIDTH[size] },
            maxWidth: "100%",
            display: "flex",
            flexDirection: "column",
            backgroundImage: "none",
          },
        },
      }}
    >
      <Box sx={{
        display: "flex", alignItems: "flex-start", gap: 1.5, px: 2.5, py: 2,
        borderBottom: "1px solid", borderColor: "divider", flexShrink: 0,
      }}>
        {icon && (
          <Box sx={{
            width: 36, height: 36, borderRadius: 2, flexShrink: 0, display: "grid", placeItems: "center",
            bgcolor: "action.hover", color: "primary.main", "& svg": { fontSize: 20 },
          }}>{icon}</Box>
        )}
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography variant="h6" sx={{ fontWeight: 800, lineHeight: 1.25 }}>{title}</Typography>
          {subtitle && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>{subtitle}</Typography>
          )}
        </Box>
        <IconButton onClick={requestClose} aria-label={t("Cerrar")} size="small" sx={{ mt: -0.5, mr: -0.5 }}>
          <CloseRounded />
        </IconButton>
      </Box>

      <Box sx={{ flex: 1, overflowY: "auto", px: 2.5, py: 2.5 }}>
        {loading ? <LoadingState /> : children}
      </Box>

      {footer && (
        <>
          <Divider />
          <Box sx={{
            display: "flex", justifyContent: "flex-end", gap: 1, px: 2.5, py: 1.75, flexShrink: 0,
            bgcolor: "background.paper",
          }}>
            {footer}
          </Box>
        </>
      )}
    </Drawer>
  );
}
