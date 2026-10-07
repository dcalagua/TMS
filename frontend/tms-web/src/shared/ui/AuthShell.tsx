import type { ReactNode } from "react";
import {
  Box, IconButton, Paper, ToggleButton, ToggleButtonGroup, Typography, useMediaQuery, useTheme,
} from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  ApartmentRounded, DarkModeRounded, Inventory2Rounded, LightModeRounded, SpeedRounded, VerifiedUserRounded,
} from "@mui/icons-material";
import { EbimMark } from "./EbimLogo";
import { useColorMode } from "../../lib/colorMode";
import { getLang, setLang, t } from "../../lib/i18n";
import { R, T, surfaceRaised } from "../../theme";

/** Qué hace el producto, en sus propias palabras. Tres es lo que cabe sin que el panel se
 * convierta en una lista de características que nadie lee. */
const FEATURES = [
  { icon: <Inventory2Rounded />, title: "Pedidos y maestros", text: "Orígenes, destinos, zonas y frecuencias listos para planificar." },
  { icon: <SpeedRounded />, title: "Planifica con control", text: "Ningún viaje sale cargado por encima de su capacidad." },
  { icon: <ApartmentRounded />, title: "Multiempresa", text: "Cada compañía con sus datos y sus propios permisos." },
] as const;

/**
 * El marco de las pantallas sin sesión (acceso y nueva contraseña). Es el diseño de
 * `design/tms.pen` → "Login · eTMS", el mismo que usa el acceso del resto de la suite.
 *
 * Una tarjeta partida en dos mitades: la marca a la izquierda sobre `surface.raised`, sin el
 * degradado del sidebar, y el formulario a la derecha, estrecho a propósito. Los textos de
 * marca van en `accentDeep` y no en el acento: es la variante que pasa AA como texto.
 *
 * Por debajo de `lg` el panel de marca se quita en vez de apilarse: en un teléfono solo
 * empujaría los campos por debajo del pliegue.
 */
export function AuthShell({ children }: { children: ReactNode }) {
  const theme = useTheme();
  const isNarrow = useMediaQuery(theme.breakpoints.down("lg"));
  const isDark = theme.palette.mode === "dark";
  const { mode, toggle: toggleMode } = useColorMode();
  const lang = getLang();
  const brandText = theme.palette.accentDeep;

  return (
    <Box sx={{
      minHeight: "100vh", display: "grid", placeItems: "center", p: { xs: 1.5, sm: 3 },
      bgcolor: "background.default", position: "relative",
    }}>
      {/* Mobiliario de la visita, no del formulario: idioma y apariencia en la esquina. */}
      <Box sx={{ position: "absolute", top: 20, right: 20, display: "flex", alignItems: "center", gap: 1, zIndex: 2 }}>
        <ToggleButtonGroup
          size="small"
          exclusive
          value={lang}
          onChange={(_, next: string | null) => next && setLang(next as "es" | "en")}
          aria-label={t("Idioma")}
          sx={{
            bgcolor: "background.paper", border: 1, borderColor: "divider", borderRadius: R.pill, p: 0.375,
            "& .MuiToggleButton-root": {
              border: 0, borderRadius: R.pill, px: 1.35, py: 0.2, minHeight: 0,
              fontSize: T.micro, fontWeight: 800, color: "text.secondary",
            },
            "& .Mui-selected": { bgcolor: (th) => `${alpha(th.palette.primary.main, 0.12)} !important`, color: brandText },
          }}
        >
          <ToggleButton value="es">ES</ToggleButton>
          <ToggleButton value="en">EN</ToggleButton>
        </ToggleButtonGroup>

        <IconButton
          size="small"
          onClick={toggleMode}
          aria-label={mode === "dark" ? t("Modo claro") : t("Modo oscuro")}
          title={mode === "dark" ? t("Modo claro") : t("Modo oscuro")}
          sx={{ bgcolor: "background.paper", border: 1, borderColor: "divider", borderRadius: `${R.sm}px` }}
        >
          {mode === "dark" ? <LightModeRounded fontSize="small" /> : <DarkModeRounded fontSize="small" />}
        </IconButton>
      </Box>

      <Paper
        variant="outlined"
        sx={{
          display: "grid",
          gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" },
          width: "100%", maxWidth: 1000, minHeight: { lg: 600 },
          overflow: "hidden", borderRadius: `${R.xl}px`,
          boxShadow: (th) => `0 24px 60px ${alpha(isDark ? th.palette.common.black : "#0A2A2A", isDark ? 0.55 : 0.12)}`,
        }}
      >
        {!isNarrow && (
          <Box sx={{
            bgcolor: surfaceRaised(isDark), borderRight: 1, borderColor: "divider",
            px: 5.5, pt: 5.5, pb: 5, display: "flex", flexDirection: "column", justifyContent: "space-between", gap: 4,
          }}>
            {/* Sin `alignSelf` la columna estira el contenedor a todo el ancho y el giro de la
                marca orbita alrededor del centro del panel en vez del suyo. */}
            <Box sx={{ alignSelf: "flex-start", lineHeight: 0 }}>
              <EbimMark size={32} color={brandText} animated />
            </Box>

            <Box>
              <Typography sx={{ fontSize: 44, fontWeight: 800, letterSpacing: "-0.03em", lineHeight: 1, color: brandText }}>
                eTMS
              </Typography>
              <Typography sx={{
                mt: 1.5, mb: 2.75, textTransform: "uppercase", letterSpacing: ".18em", fontSize: T.label,
                fontWeight: 800, color: brandText,
              }}>
                {t("Gestión de transporte (TMS)")}
              </Typography>
              <Typography sx={{ fontSize: 15, lineHeight: 1.55, color: "text.secondary", maxWidth: 400 }}>
                {t("Del pedido al viaje sin planillas intermedias: capacidad, rutas y flota en un solo sistema, con cada empresa viendo solo lo suyo.")}
              </Typography>

              <Box component="ul" sx={{ listStyle: "none", p: 0, m: 0, mt: 3.75, display: "grid", gap: 2.25 }}>
                {FEATURES.map((feature) => (
                  <Box component="li" key={feature.title} sx={{ display: "flex", gap: 1.75, alignItems: "center" }}>
                    <Box aria-hidden sx={{
                      width: 38, height: 38, borderRadius: "10px", flexShrink: 0, display: "grid", placeItems: "center",
                      bgcolor: (th) => alpha(th.palette.primary.main, isDark ? 0.18 : 0.1), color: brandText,
                      "& svg": { fontSize: 19 },
                    }}>
                      {feature.icon}
                    </Box>
                    <Box>
                      <Typography sx={{ fontWeight: 700, fontSize: 14, lineHeight: 1.35 }}>{t(feature.title)}</Typography>
                      <Typography sx={{ fontSize: T.body, color: "text.secondary", lineHeight: 1.4 }}>{t(feature.text)}</Typography>
                    </Box>
                  </Box>
                ))}
              </Box>
            </Box>

            <Typography sx={{
              display: "flex", alignItems: "center", gap: 1, fontSize: 12.5, fontWeight: 500, color: "text.secondary",
            }}>
              <VerifiedUserRounded sx={{ fontSize: 15, color: brandText }} />
              {t("Conexión cifrada · Datos aislados por compañía")}
            </Typography>
          </Box>
        )}

        <Box component="main" sx={{
          bgcolor: "background.paper", px: { xs: 3, sm: 7.5 }, py: { xs: 4, sm: 5 },
          display: "flex", flexDirection: "column", justifyContent: "center", alignItems: "center",
        }}>
          <Box sx={{ width: "100%", maxWidth: 380 }}>
            {isNarrow && (
              <Box sx={{ display: "flex", mb: 3 }}>
                <EbimMark size={30} color={brandText} />
              </Box>
            )}
            {children}

            {/* La firma cierra el panel: después de las acciones, discreta y legible (AA). */}
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 1, mt: 3.75 }}>
              <EbimMark size={18} color={brandText} />
              <Box sx={{ lineHeight: 1 }}>
                <Typography sx={{ fontSize: 14, fontWeight: 800, lineHeight: 1 }}>eTMS</Typography>
                <Typography sx={{ fontSize: 8.5, letterSpacing: ".18em", fontWeight: 700, color: "text.secondary" }}>
                  BY EBIM
                </Typography>
              </Box>
            </Box>
          </Box>
        </Box>
      </Paper>
    </Box>
  );
}

/** Un campo con su etiqueta encima, no dentro del borde: se lee antes y mientras se escribe. */
export function LabelledField({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  return (
    <Box sx={{ mb: 2.25 }}>
      <Typography
        component="label"
        htmlFor={id}
        sx={{ display: "block", mb: 1, fontSize: T.bodyStrong, fontWeight: 700, color: "text.primary" }}
      >
        {label}
      </Typography>
      {children}
    </Box>
  );
}
