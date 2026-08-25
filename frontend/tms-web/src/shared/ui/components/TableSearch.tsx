import { InputAdornment, TextField, IconButton } from "@mui/material";
import { SearchRounded, CloseRounded } from "@mui/icons-material";
import { t } from "../../../lib/i18n";

interface TableSearchProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  /** Ancho fijo; por defecto se adapta al hueco de la cabecera. */
  width?: number | string;
}

/** El buscador que acompaña a una tabla, en la cabecera de página. Filtra en cliente o dispara
 * la query, según decida la pantalla: aquí solo vive la caja. */
export function TableSearch({ value, onChange, placeholder, width = 240 }: TableSearchProps) {
  return (
    <TextField
      size="small"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder ?? t("Buscar")}
      aria-label={placeholder ?? t("Buscar")}
      sx={{ width, maxWidth: "100%" }}
      slotProps={{
        input: {
          startAdornment: (
            <InputAdornment position="start">
              <SearchRounded fontSize="small" sx={{ color: "text.disabled" }} />
            </InputAdornment>
          ),
          endAdornment: value ? (
            <InputAdornment position="end">
              <IconButton size="small" onClick={() => onChange("")} aria-label={t("Limpiar")}>
                <CloseRounded fontSize="small" />
              </IconButton>
            </InputAdornment>
          ) : undefined,
        },
      }}
    />
  );
}
