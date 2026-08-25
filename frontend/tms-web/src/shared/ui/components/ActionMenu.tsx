import { useState, type MouseEvent, type ReactNode } from "react";
import { IconButton, ListItemIcon, ListItemText, Menu, MenuItem, Tooltip, Divider } from "@mui/material";
import { MoreVertRounded } from "@mui/icons-material";
import { t } from "../../../lib/i18n";

export interface ActionMenuItem {
  key: string;
  label: string;
  icon?: ReactNode;
  onSelect: () => void;
  dangerous?: boolean;
  disabled?: boolean;
  /** Traza una separación por encima de este ítem: agrupa lo destructivo aparte de lo normal. */
  divider?: boolean;
}

export interface ActionMenuProps {
  items: ActionMenuItem[];
  /** Sustituye el nombre accesible del disparador. */
  label?: string;
}

/**
 * El menú `⋮` que usa una fila de tabla para sus acciones secundarias, para que una lista de
 * veinte filas no sea un muro de cuarenta botones.
 *
 * El panel lo monta MUI en un portal fuera del flujo de la tabla: una fila vive dentro de un
 * contenedor con `overflow`, y un hijo posicionado en absoluto quedaría recortado por él — el
 * menú de la última fila se abriría por detrás del borde del panel. Subir el z-index no arregla
 * eso; un contenedor con overflow recorta lo que sea que diga el orden de apilado.
 */
export function ActionMenu({ items, label }: ActionMenuProps) {
  const [anchor, setAnchor] = useState<null | HTMLElement>(null);

  if (items.length === 0) return null;

  const triggerLabel = label ?? t("Abrir menú de acciones");

  const open = (event: MouseEvent<HTMLElement>) => {
    event.stopPropagation();
    setAnchor(event.currentTarget);
  };

  const choose = (item: ActionMenuItem) => {
    setAnchor(null);
    item.onSelect();
  };

  return (
    <>
      <Tooltip title={triggerLabel}>
        <IconButton size="small" onClick={open} aria-label={triggerLabel} aria-haspopup="menu">
          <MoreVertRounded fontSize="small" />
        </IconButton>
      </Tooltip>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        onClick={(e) => e.stopPropagation()}
        anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
        transformOrigin={{ vertical: "top", horizontal: "right" }}
        slotProps={{
          paper: {
            sx: {
              mt: 0.5, borderRadius: 2.5, minWidth: 208, overflow: "hidden",
              boxShadow: "0 12px 32px rgba(0,0,0,0.18)",
              "& .MuiList-root": { py: 0.75 },
              "& .MuiMenuItem-root": { mx: 0.75, px: 1.25, py: 0.85, borderRadius: 1.5, fontSize: 13.5, fontWeight: 600 },
            },
          },
        }}
      >
        {items.map((item) => [
          item.divider ? <Divider key={`${item.key}-div`} sx={{ my: 0.75 }} /> : null,
          <MenuItem
            key={item.key}
            disabled={item.disabled}
            onClick={() => choose(item)}
            sx={item.dangerous ? { color: "error.main", "& .MuiListItemIcon-root": { color: "error.main" } } : undefined}
          >
            {item.icon && <ListItemIcon sx={{ minWidth: 32, "& svg": { fontSize: 19 } }}>{item.icon}</ListItemIcon>}
            <ListItemText slotProps={{ primary: { sx: { fontSize: 13.5, fontWeight: 600 } } }}>{item.label}</ListItemText>
          </MenuItem>,
        ])}
      </Menu>
    </>
  );
}
