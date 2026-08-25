import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Avatar, Box, Button, Divider, ListItemIcon, Menu, MenuItem, Tooltip, Typography,
  useMediaQuery, useTheme,
} from "@mui/material";
import {
  KeyboardArrowDownRounded, PersonRounded, LogoutRounded, CheckRounded,
  PaletteRounded, LanguageRounded, DensityMediumRounded,
} from "@mui/icons-material";
import { useColorMode } from "../../lib/colorMode";
import { DENSITY_LIST, THEME_LIST, type Density, type ThemeKey } from "../../theme";
import { getLang, setLang, t } from "../../lib/i18n";
import { confirmDialog } from "../../lib/ui";
import { useAuth } from "../auth/AuthContext";
import { useCompany } from "../company/CompanyContext";

/**
 * La cápsula de cuenta de la barra superior y su menú.
 *
 * El idioma, el tema de color y la densidad viven aquí y no en la barra: son ajustes que se
 * tocan una vez cada varios meses, y tres iconos permanentes por ellos le quitan sitio a lo que
 * sí se usa cada día. Es además donde los pone el resto de la suite, así que un operador que
 * viene de eGMAO ya sabe dónde buscarlos.
 */
export function AccountMenu({ iconSx }: { iconSx?: object }) {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down("md"));
  const navigate = useNavigate();
  const { user, signOut } = useAuth();
  const { profile, selected } = useCompany();
  const { mode, themeKey, setThemeKey, density, setDensity } = useColorMode();
  const [anchor, setAnchor] = useState<null | HTMLElement>(null);

  const email = profile?.email ?? user?.email ?? "";
  const name = profile?.fullName || email;
  const initial = (name || "?").charAt(0).toUpperCase();
  const lang = getLang();

  async function logout() {
    setAnchor(null);
    const confirmed = await confirmDialog({
      title: t("¿Cerrar sesión?"),
      text: t("Deberás iniciar sesión nuevamente para continuar."),
      confirmLabel: t("Cerrar sesión"),
    });
    if (confirmed) await signOut();
  }

  const swatch = (key: ThemeKey, colors: string[], selectedKey: boolean, onClick: () => void) => (
    <Box
      key={key} onClick={onClick} role="button" aria-label={key}
      sx={{
        width: 24, height: 24, borderRadius: "50%", cursor: "pointer", flexShrink: 0,
        background: colors.length > 1 ? `linear-gradient(135deg, ${colors.join(", ")})` : colors[0],
        boxShadow: "inset 0 0 0 1px rgba(0,0,0,0.12)",
        outline: selectedKey ? "2px solid" : "none", outlineColor: "primary.main", outlineOffset: "2px",
        display: "grid", placeItems: "center", "& svg": { fontSize: 14, color: "#fff" },
      }}
    >
      {selectedKey && <CheckRounded />}
    </Box>
  );

  return (
    <>
      <Tooltip title={t("Cuenta")}>
        <Button
          onClick={(e) => setAnchor(e.currentTarget)} color="inherit"
          sx={{
            textTransform: "none", borderRadius: 99, minWidth: 0,
            pl: 0.6, pr: isMobile ? 0.6 : 1.1, py: 0.45,
            border: "1px solid", borderColor: "divider", bgcolor: "background.default",
            transition: "background-color .15s, border-color .15s",
            "&:hover": { bgcolor: "action.hover", borderColor: "text.disabled" },
            ...iconSx,
          }}
        >
          <Avatar sx={{
            bgcolor: "primary.main", color: "primary.contrastText", width: 30, height: 30,
            fontSize: 13, fontWeight: 700,
          }}>
            {initial}
          </Avatar>
          {!isMobile && (
            <>
              <Box sx={{ ml: 1, textAlign: "left", maxWidth: 150 }}>
                <Typography noWrap sx={{ fontWeight: 700, lineHeight: 1.15, fontSize: 13 }}>
                  {name}
                </Typography>
                {selected && (
                  <Typography noWrap sx={{ color: "text.secondary", display: "block", lineHeight: 1, fontSize: 10.5 }}>
                    {selected.code}
                  </Typography>
                )}
              </Box>
              <KeyboardArrowDownRounded sx={{ ml: 0.4, fontSize: 17, color: "text.secondary" }} />
            </>
          )}
        </Button>
      </Tooltip>

      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
        transformOrigin={{ vertical: "top", horizontal: "right" }}
        slotProps={{ paper: { sx: { mt: 1, width: 300, borderRadius: 2.5, overflow: "hidden" } } }}
      >
        <Box sx={{ px: 2, py: 1.5 }}>
          <Typography variant="subtitle1" noWrap>{name}</Typography>
          <Typography variant="caption" color="text.secondary" noWrap sx={{ display: "block" }}>{email}</Typography>
        </Box>
        <Divider />

        <MenuItem onClick={() => { setAnchor(null); navigate("/account"); }} sx={{ py: 1.1 }}>
          <ListItemIcon><PersonRounded fontSize="small" /></ListItemIcon>
          {t("Mi cuenta")}
        </MenuItem>

        <Divider />

        {/* Tema de color: los swatches del contrato de Apariencia de la suite. */}
        <Box sx={{ px: 2, pt: 1.25, pb: 1 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
            <PaletteRounded sx={{ fontSize: 18, color: "text.secondary" }} />
            <Typography variant="caption" sx={{ fontWeight: 700, textTransform: "uppercase", letterSpacing: ".06em", color: "text.secondary" }}>
              {t("Tema")}
            </Typography>
          </Box>
          <Box sx={{ display: "flex", gap: 1.25, flexWrap: "wrap" }}>
            {THEME_LIST.map((item) => (
              <Tooltip key={item.key} title={`${item.label} · ${item.sub}`}>
                <span>{swatch(item.key, item.swatch, themeKey === item.key, () => setThemeKey(item.key))}</span>
              </Tooltip>
            ))}
          </Box>
        </Box>

        <Divider sx={{ mt: 1 }} />

        {/* Densidad: el mismo contrato de tres pasos que el resto de la suite. */}
        <Box sx={{ px: 2, pt: 1.25, pb: 1 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
            <DensityMediumRounded sx={{ fontSize: 18, color: "text.secondary" }} />
            <Typography variant="caption" sx={{ fontWeight: 700, textTransform: "uppercase", letterSpacing: ".06em", color: "text.secondary" }}>
              {t("Densidad")}
            </Typography>
          </Box>
          <Box sx={{ display: "flex", gap: 0.75 }}>
            {DENSITY_LIST.map((item) => (
              <Button
                key={item.key}
                size="small"
                variant={density === item.key ? "contained" : "outlined"}
                onClick={() => setDensity(item.key as Density)}
                sx={{ flex: 1, minHeight: 30, fontSize: 11.5, px: 0.5 }}
              >
                {t(item.label)}
              </Button>
            ))}
          </Box>
        </Box>

        <Divider sx={{ mt: 1 }} />

        {/* Idioma. Cambiarlo recarga: el diccionario se lee en el render, no reactivamente. */}
        <Box sx={{ px: 2, pt: 1.25, pb: 1.25 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
            <LanguageRounded sx={{ fontSize: 18, color: "text.secondary" }} />
            <Typography variant="caption" sx={{ fontWeight: 700, textTransform: "uppercase", letterSpacing: ".06em", color: "text.secondary" }}>
              {t("Idioma")}
            </Typography>
          </Box>
          <Box sx={{ display: "flex", gap: 0.75 }}>
            {(["es", "en"] as const).map((code) => (
              <Button
                key={code}
                size="small"
                variant={lang === code ? "contained" : "outlined"}
                onClick={() => setLang(code)}
                sx={{ flex: 1, minHeight: 30, fontSize: 11.5 }}
              >
                {code === "es" ? t("Español") : t("Inglés")}
              </Button>
            ))}
          </Box>
        </Box>

        <Divider />

        <MenuItem onClick={logout} sx={{ py: 1.1, color: "error.main" }}>
          <ListItemIcon><LogoutRounded fontSize="small" sx={{ color: "error.main" }} /></ListItemIcon>
          {t("Cerrar sesión")}
        </MenuItem>

        <Box sx={{ px: 2, pb: 1 }}>
          <Typography variant="caption" color="text.disabled">
            {mode === "dark" ? t("Modo oscuro") : t("Modo claro")}
          </Typography>
        </Box>
      </Menu>
    </>
  );
}
