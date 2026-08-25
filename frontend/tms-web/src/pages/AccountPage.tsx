import { Box, Button, Typography } from "@mui/material";
import { PersonRounded, PaletteRounded, LanguageRounded, DensityMediumRounded } from "@mui/icons-material";
import { useAuth } from "../shared/auth/AuthContext";
import { useCompany } from "../shared/company/CompanyContext";
import { AppCard, DetailGrid, DetailItem, PageHeader, SectionHeader } from "../shared/ui/components";
import { useColorMode } from "../lib/colorMode";
import { DENSITY_LIST, THEME_LIST, type Density } from "../theme";
import { getLang, setLang, t } from "../lib/i18n";

/**
 * El perfil de la persona, fuera del ámbito de empresa a propósito: una cuenta es de quien la
 * usa, no de la compañía a la que la sesión resulte estar apuntando en ese momento.
 *
 * Los ajustes de apariencia también viven en el menú de la cuenta de la barra superior. Están
 * aquí además porque un menú desplegable es donde se cambian a diario y esta pantalla es donde
 * se descubren.
 */
export function AccountPage() {
  const { user } = useAuth();
  const { profile, selected, companies } = useCompany();
  const { mode, toggle: toggleMode, themeKey, setThemeKey, density, setDensity } = useColorMode();
  const lang = getLang();

  return (
    <>
      <PageHeader
        icon={<PersonRounded />}
        tint="#B0BEC5"
        title={t("Mi cuenta")}
        subtitle={t("Nombre y contraseña")}
      />

      <Box sx={{ display: "grid", gap: 3, gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" } }}>
        <AppCard title={t("Identidad")}>
          <DetailGrid>
            <DetailItem label={t("Nombre")} value={profile?.fullName ?? "-"} />
            <DetailItem label={t("Correo electrónico")} value={profile?.email ?? user?.email ?? "-"} />
            <DetailItem label={t("Compañía activa")} value={selected?.name ?? "-"} />
            <DetailItem label={t("Organización")} value={selected?.organization.name ?? "-"} />
            <DetailItem
              span
              label={t("Compañías con acceso")}
              value={companies.length === 0 ? "-" : companies.map((c) => c.name).join(" · ")}
            />
          </DetailGrid>
        </AppCard>

        <AppCard title={t("Apariencia")}>
          <SectionHeader title={t("Tema")} />
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1, mb: 2 }}>
            {THEME_LIST.map((item) => (
              <Button
                key={item.key}
                size="small"
                variant={themeKey === item.key ? "contained" : "outlined"}
                onClick={() => setThemeKey(item.key)}
                startIcon={
                  <Box sx={{
                    width: 16, height: 16, borderRadius: "50%",
                    background: `linear-gradient(135deg, ${item.swatch.join(", ")})`,
                  }} />
                }
              >
                {item.label}
              </Button>
            ))}
          </Box>

          <SectionHeader title={t("Densidad")} />
          <Box sx={{ display: "flex", gap: 1, mb: 2 }}>
            {DENSITY_LIST.map((item) => (
              <Button
                key={item.key}
                size="small"
                variant={density === item.key ? "contained" : "outlined"}
                onClick={() => setDensity(item.key as Density)}
                startIcon={<DensityMediumRounded />}
              >
                {t(item.label)}
              </Button>
            ))}
          </Box>

          <SectionHeader title={t("Idioma")} />
          <Box sx={{ display: "flex", gap: 1, mb: 2 }}>
            {(["es", "en"] as const).map((code) => (
              <Button
                key={code}
                size="small"
                variant={lang === code ? "contained" : "outlined"}
                onClick={() => setLang(code)}
                startIcon={<LanguageRounded />}
              >
                {code === "es" ? t("Español") : t("Inglés")}
              </Button>
            ))}
          </Box>

          <SectionHeader title={t("Modo")} />
          <Button size="small" variant="outlined" onClick={toggleMode} startIcon={<PaletteRounded />}>
            {mode === "dark" ? t("Modo claro") : t("Modo oscuro")}
          </Button>

          <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 2 }}>
            {t("El idioma se aplica recargando la pantalla.")}
          </Typography>
        </AppCard>
      </Box>
    </>
  );
}
