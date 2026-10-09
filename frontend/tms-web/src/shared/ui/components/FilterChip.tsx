import { useState, type ReactNode } from "react";
import { Box, Button, MenuItem, MenuList, Popover, Typography } from "@mui/material";
import { alpha } from "@mui/material/styles";
import { CloseRounded, KeyboardArrowDownRounded } from "@mui/icons-material";
import { t } from "../../../lib/i18n";
import { R } from "../../../theme";

export interface FilterOption { id: string; label: string }

/**
 * Un filtro en forma de chip: sin valor se lee como «Origen ⌄»; con valor, en el color de acción,
 * con el valor y una ✕ para quitarlo sin abrir el menú.
 */
export function FilterMenuChip({ icon, label, valueLabel, onClear, children }: {
  icon: ReactNode;
  label: string;
  valueLabel: string | null;
  onClear: () => void;
  children: (close: () => void) => ReactNode;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const active = valueLabel !== null;
  const close = () => setAnchor(null);

  return (
    <>
      <Button
        variant="outlined"
        color={active ? "primary" : "inherit"}
        onClick={(e) => setAnchor(e.currentTarget)}
        aria-haspopup="dialog"
        aria-expanded={anchor !== null}
        startIcon={icon}
        endIcon={active ? (
          <Box
            component="span"
            role="button"
            tabIndex={0}
            aria-label={t("Quitar filtro {{name}}", { name: label })}
            onClick={(e) => { e.stopPropagation(); onClear(); }}
            onKeyDown={(e) => {
              if (e.key !== "Enter" && e.key !== " ") return;
              e.preventDefault(); e.stopPropagation(); onClear();
            }}
            sx={{ display: "inline-flex", borderRadius: "50%", "&:hover": { bgcolor: "action.hover" } }}
          >
            <CloseRounded sx={{ fontSize: "16px !important" }} />
          </Box>
        ) : <KeyboardArrowDownRounded />}
        sx={(th) => ({
          height: 36, px: 1.5, fontWeight: 600, whiteSpace: "nowrap", flexShrink: 0,
          borderColor: active ? "primary.main" : "divider",
          color: active ? "primary.main" : "text.secondary",
          bgcolor: active ? alpha(th.palette.primary.main, th.palette.mode === "dark" ? 0.16 : 0.07) : "background.paper",
          "& .MuiButton-startIcon svg": { fontSize: 17 },
        })}
      >
        {label}
        {active && (
          <Typography component="span" sx={{ ml: 0.75, fontSize: "inherit", fontWeight: 800, maxWidth: 180 }} noWrap>
            {valueLabel}
          </Typography>
        )}
      </Button>
      <Popover
        open={anchor !== null}
        anchorEl={anchor}
        onClose={close}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
        slotProps={{ paper: { sx: { mt: 0.75, borderRadius: `${R.md}px`, minWidth: 220 } } }}
      >
        {children(close)}
      </Popover>
    </>
  );
}

export function FilterOptionList({ options, value, allLabel, onSelect }: {
  options: FilterOption[]; value: string; allLabel?: string; onSelect: (id: string) => void;
}) {
  return (
    <MenuList dense sx={{ maxHeight: 340, overflowY: "auto", py: 0.5 }}>
      {allLabel !== undefined && <MenuItem selected={value === ""} onClick={() => onSelect("")}>{allLabel}</MenuItem>}
      {options.map((option) => (
        <MenuItem key={option.id} selected={value === option.id} onClick={() => onSelect(option.id)}>
          {option.label}
        </MenuItem>
      ))}
    </MenuList>
  );
}
