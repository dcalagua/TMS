import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Autocomplete, Box, InputAdornment, TextField, Typography,
} from "@mui/material";
import { SearchRounded } from "@mui/icons-material";
import { ALL_NAV_LEAVES, ICON_TINTS, DEFAULT_TINT, NAV_SECTIONS, type NavLeaf } from "./navConfig";
import { useCompany } from "../company/CompanyContext";
import { t } from "../../lib/i18n";
import { R } from "../../theme";
import { alpha } from "@mui/material/styles";

/** La sección a la que pertenece cada hoja, para agrupar los resultados igual que el menú. */
const SECTION_OF: Record<string, string> = Object.fromEntries(
  NAV_SECTIONS.flatMap((s) => s.items.map((i) => [i.to, s.title])),
);

/**
 * El buscador central de la barra superior: encuentra una pantalla por su nombre en vez de
 * obligar a recorrer el menú.
 *
 * Solo ofrece lo que la cuenta puede abrir. Tres consumidores del menú —la barra lateral, este
 * buscador y las migas— comparten la misma lista y el mismo filtro de capabilities: tres copias
 * de esa condición es como la tercera acaba ofreciendo una pantalla que el menú está
 * escondiendo.
 */
export function NavSearch() {
  const navigate = useNavigate();
  const { hasCapability, status } = useCompany();
  const [value, setValue] = useState("");

  const options = useMemo(() => {
    const visible = (leaf: NavLeaf) => {
      // Antes de que `/me` responda no se sabe nada: mejor no ofrecer nada que ofrecer de más.
      if (leaf.capability === undefined) return true;
      return status === "ready" && hasCapability(leaf.capability);
    };
    return ALL_NAV_LEAVES.filter((leaf) => {
      const section = NAV_SECTIONS.find((s) => s.items.includes(leaf));
      if (section?.capability && !(status === "ready" && hasCapability(section.capability))) return false;
      return visible(leaf);
    });
  }, [hasCapability, status]);

  return (
    <Autocomplete<NavLeaf, false, false, false>
      size="small"
      options={options}
      value={null}
      inputValue={value}
      onInputChange={(_e, next) => setValue(next)}
      onChange={(_e, leaf) => {
        if (leaf) { navigate(leaf.to); setValue(""); }
      }}
      getOptionLabel={(option) => t(option.label)}
      groupBy={(option) => t(SECTION_OF[option.to] ?? "Inicio")}
      noOptionsText={t("Sin resultados")}
      blurOnSelect
      clearOnBlur
      sx={{ width: { xs: 180, sm: 280, md: 380 }, maxWidth: "100%" }}
      renderOption={(props, option) => {
        const { key, ...rest } = props as { key: string } & Record<string, unknown>;
        const tint = ICON_TINTS[option.to] ?? DEFAULT_TINT;
        return (
          <Box component="li" key={key} {...rest} sx={{ gap: 1.25 }}>
            <Box sx={{
              width: 26, height: 26, borderRadius: 1.75, flexShrink: 0, display: "grid", placeItems: "center",
              bgcolor: alpha(tint, 0.18), color: tint, "& svg": { fontSize: 16 },
            }}>
              {option.icon}
            </Box>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>{t(option.label)}</Typography>
          </Box>
        );
      }}
      renderInput={(params) => (
        <TextField
          {...params}
          placeholder={t("Buscar")}
          aria-label={t("Buscar")}
          slotProps={{
            ...params.slotProps,
            input: {
              ...params.slotProps.input,
              startAdornment: (
                <InputAdornment position="start">
                  <SearchRounded fontSize="small" sx={{ color: "text.secondary" }} />
                </InputAdornment>
              ),
            },
          }}
          sx={{
            // El buscador vive ahora sobre el papel de la barra, así que se pinta con el
            // lienzo por fondo: sobre blanco, un campo blanco no se distingue de la barra.
            "& .MuiOutlinedInput-root": {
              bgcolor: "background.default",
              borderRadius: `${R.md}px`,
              transition: "background-color .15s, border-color .15s",
              "& fieldset": { borderColor: "divider" },
              "&:hover fieldset": { borderColor: "text.disabled" },
            },
            "& .MuiOutlinedInput-input::placeholder": { color: "text.secondary", opacity: 1 },
            "& .MuiAutocomplete-clearIndicator, & .MuiAutocomplete-popupIndicator": { color: "text.secondary" },
          }}
        />
      )}
    />
  );
}
