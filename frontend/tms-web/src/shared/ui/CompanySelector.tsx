import { MenuItem, Select, Tooltip, Typography, Box } from "@mui/material";
import { ApartmentRounded } from "@mui/icons-material";
import { useCompany } from "../company/CompanyContext";
import { t } from "../../lib/i18n";
import { R, T } from "../../theme";

/**
 * El selector de empresa de la barra superior, en el mismo sitio en el que el resto de la suite
 * pone su selector de ámbito.
 *
 * Elegir aquí solo cambia qué `X-Company-Id` mandan las peticiones siguientes. El backend
 * valida esa cabecera por su cuenta en cada llamada, así que esto es orientación y comodidad,
 * nunca una frontera de seguridad.
 *
 * Con una sola empresa no hay nada que elegir: se muestra cuál es, y punto — un desplegable de
 * una opción es una promesa vacía.
 */
export function CompanySelector() {
  const { companies, selected, selectCompany } = useCompany();

  if (companies.length === 0 || !selected) return null;

  if (companies.length === 1) {
    return (
      <Tooltip title={t("Empresa activa")}>
        <Box sx={{
          mr: 1, display: "flex", alignItems: "center", gap: 0.9, maxWidth: 280,
          px: 1.3, py: 0.65, borderRadius: `${R.md}px`,
          bgcolor: "background.default", border: "1px solid", borderColor: "divider",
        }}>
          <ApartmentRounded sx={{ fontSize: 16, color: "text.secondary" }} />
          <Typography noWrap sx={{ fontWeight: 700, fontSize: T.body }}>
            {selected.name}
          </Typography>
        </Box>
      </Tooltip>
    );
  }

  return (
    <Select
      size="small"
      value={selected.id}
      onChange={(e) => selectCompany(String(e.target.value))}
      variant="standard"
      disableUnderline
      aria-label={t("Cambiar empresa")}
      startAdornment={<ApartmentRounded sx={{ fontSize: 16, color: "text.secondary", ml: 1.3, mr: 0.9 }} />}
      MenuProps={{
        slotProps: {
          paper: {
            sx: {
              mt: 0.75, borderRadius: 2.5, minWidth: 260, overflow: "hidden",
              boxShadow: "0 12px 32px rgba(0,0,0,0.18)",
              "& .MuiList-root": { py: 0.75 },
              "& .MuiMenuItem-root": { mx: 0.75, px: 1.25, py: 0.9, borderRadius: 1.5, fontSize: 13.5, fontWeight: 600 },
            },
          },
        },
      }}
      sx={{
        mr: 1, maxWidth: 300, fontWeight: 700, fontSize: T.body,
        bgcolor: "background.default", borderRadius: `${R.md}px`,
        border: "1px solid", borderColor: "divider",
        transition: "background-color .15s, border-color .15s",
        "&:hover": { borderColor: "text.disabled" },
        "& .MuiSelect-icon": { color: "text.secondary", right: 6 },
        "& .MuiSelect-select": {
          py: 0.65, pl: 0, pr: "28px !important", display: "flex", alignItems: "center",
          whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
        },
      }}
    >
      {companies.map((company) => (
        <MenuItem key={company.id} value={company.id}>
          <Box sx={{ minWidth: 0 }}>
            <Typography noWrap sx={{ fontWeight: 700, fontSize: 13.5 }}>{company.name}</Typography>
            <Typography noWrap variant="caption" color="text.secondary">
              {company.code} · {company.organization.name}
            </Typography>
          </Box>
        </MenuItem>
      ))}
    </Select>
  );
}
