import { Chip, useTheme, type ChipProps } from "@mui/material";
import { STATUS, type StatusTone } from "../../../theme";

interface StatusChipProps {
  label: string;
  /** Tono semántico. `neutral` es el gris de reposo: un estado que no es ni bueno ni malo. */
  tone?: StatusTone;
  /** `soft` (por defecto) es el chip sutil de las tablas; `solid` el relleno, para destacar uno. */
  variant?: "soft" | "solid";
  size?: ChipProps["size"];
  icon?: ChipProps["icon"];
  onClick?: () => void;
}

/**
 * El chip de estado de toda la suite, pintado desde los tokens `STATUS` de `theme.ts` — que ya
 * traen verificado el contraste AA en claro y en oscuro.
 *
 * Es un componente y no una clase por pantalla porque el vocabulario de estados es del
 * producto, no de la tabla: el mismo verde tiene que significar "entregado" en viajes y
 * "confirmado" en planificación, o el operador deja de poder leer la lista de un vistazo.
 */
export function StatusChip({ label, tone = "neutral", variant = "soft", size = "small", icon, onClick }: StatusChipProps) {
  const theme = useTheme();
  const mode = theme.palette.mode === "dark" ? "dark" : "light";

  if (tone === "neutral") {
    const neutral = STATUS[mode].cancelled;
    return (
      <Chip
        label={label} size={size} icon={icon} onClick={onClick}
        sx={{
          bgcolor: variant === "solid" ? neutral.bg : neutral.soft,
          color: variant === "solid" ? neutral.text : neutral.softText,
          border: variant === "soft" ? "1px solid" : "none",
          borderColor: "divider",
          "& .MuiChip-icon": { color: "inherit" },
        }}
      />
    );
  }

  const token = STATUS[mode][tone];
  return (
    <Chip
      label={label} size={size} icon={icon} onClick={onClick}
      sx={{
        bgcolor: variant === "solid" ? token.bg : token.soft,
        color: variant === "solid" ? token.text : token.softText,
        "& .MuiChip-icon": { color: "inherit" },
      }}
    />
  );
}

/** Chip de activo/inactivo, el par que aparece en todos los maestros. */
export function ActiveBadge({ active, labels }: { active: boolean; labels?: { yes: string; no: string } }) {
  return (
    <StatusChip
      tone={active ? "done" : "cancelled"}
      label={active ? (labels?.yes ?? "Activo") : (labels?.no ?? "Inactivo")}
    />
  );
}
