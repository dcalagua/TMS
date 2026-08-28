import { useState, type ReactNode } from "react";
import { useForm } from "react-hook-form";
import { Navigate, useLocation } from "react-router-dom";
import {
  Alert, Box, Button, IconButton, InputAdornment, Paper, TextField, ToggleButton, ToggleButtonGroup,
  Typography, useMediaQuery, useTheme,
} from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  VisibilityRounded, VisibilityOffRounded, ShieldRounded,
  Inventory2Rounded, TuneRounded, ApartmentRounded, DarkModeRounded, LightModeRounded,
} from "@mui/icons-material";
import { useAuth } from "../shared/auth/AuthContext";
import { EbimMark } from "../shared/ui/EbimLogo";
import { useColorMode } from "../lib/colorMode";
import { R, SIDEBAR, T, brandSidebar, getTheme, type ThemeKey } from "../theme";
import { getLang, setLang, t } from "../lib/i18n";
import { ThemeProvider } from "@mui/material/styles";

interface LoginFormValues {
  email: string;
  password: string;
}

interface LocationState {
  from?: { pathname: string };
}

/** Qué hace el producto, en sus propias palabras. Tres es lo que cabe sin que el panel se
 * convierta en una lista de características que nadie lee. */
const FEATURES = [
  { icon: <Inventory2Rounded />, title: "Pedidos y maestros", text: "Orígenes, destinos, zonas y frecuencias listos para planificar." },
  { icon: <TuneRounded />, title: "Planificación con control", text: "El sistema no deja cargar un viaje por encima de su capacidad." },
  { icon: <ApartmentRounded />, title: "Multiempresa", text: "Cada compañía ve lo suyo, con permisos propios." },
] as const;

/**
 * Un campo con su etiqueta encima, no dentro del borde.
 *
 * Es lo que hace el resto de la suite en su pantalla de acceso, y aquí gana algo concreto: la
 * etiqueta se lee antes de tocar el campo y sigue leyéndose mientras se escribe, en vez de
 * encogerse hasta el tamaño de una nota al pie sobre el propio borde.
 */
function LabelledField({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  return (
    <Box sx={{ mb: 2.25 }}>
      <Typography
        component="label"
        htmlFor={id}
        sx={{ display: "block", mb: 0.85, fontSize: T.bodyStrong, fontWeight: 700, color: "text.primary" }}
      >
        {label}
      </Typography>
      {children}
    </Box>
  );
}

/**
 * Pantalla de acceso. El único sitio donde la app habla directamente con Supabase Auth, a
 * través de `useAuth().signIn` — aquí no ocurre ninguna llamada de negocio.
 *
 * La composición es la de la suite: una sola tarjeta ancha partida en dos mitades iguales, la
 * marca a la izquierda sobre el mismo degradado que la barra lateral y el formulario a la
 * derecha, centrado en su mitad y estrecho a propósito. Las dos mitades llevan geometría de
 * fondo muy tenue para que ninguna quede como un rectángulo plano.
 *
 * Por debajo de `lg` el panel de marca se quita en vez de apilarse: en un teléfono solo
 * empujaría los campos por debajo del pliegue.
 */
export function LoginPage() {
  const theme = useTheme();
  const isNarrow = useMediaQuery(theme.breakpoints.down("lg"));
  const { status, signIn } = useAuth();
  const { mode, toggle: toggleMode, themeKey, brandAccent } = useColorMode();
  const location = useLocation();
  const [formError, setFormError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<LoginFormValues>();
  const lang = getLang();

  // El panel de marca lleva el mismo gradiente y el mismo marco oscuro que la barra lateral, de
  // forma que el login ya es la aplicación antes de entrar en ella.
  const shellTheme = getTheme("dark", themeKey, brandAccent ?? undefined);
  const shellBg = themeKey === "brand"
    ? (brandAccent ? brandSidebar(brandAccent) : SIDEBAR.forest)
    : SIDEBAR[themeKey as Exclude<ThemeKey, "brand">];
  const accentSoft = shellTheme.palette.primary.light;

  if (status === "signedIn") {
    const redirectTo = (location.state as LocationState | null)?.from?.pathname ?? "/";
    return <Navigate to={redirectTo} replace />;
  }

  async function onSubmit(values: LoginFormValues) {
    setFormError(null);
    const result = await signIn(values.email, values.password);
    if (!result.ok) {
      setFormError(result.message ?? t("No se pudo iniciar sesión. Revisa tus credenciales e inténtalo de nuevo."));
    }
  }

  const fieldSx = {
    "& .MuiOutlinedInput-root": { borderRadius: `${R.md}px`, bgcolor: "background.default" },
    "& .MuiOutlinedInput-input": { py: 1.45, fontSize: T.bodyStrong },
  } as const;

  return (
    <Box sx={{
      minHeight: "100vh", display: "grid", placeItems: "center", p: { xs: 1.5, sm: 3 },
      bgcolor: "background.default",
    }}>
      <Paper
        variant="outlined"
        sx={{
          display: "grid",
          gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" },
          width: "100%", maxWidth: 1060, minHeight: { lg: 592 },
          overflow: "hidden", borderRadius: `${R.xl + 4}px`,
          boxShadow: (th) => `0 30px 70px ${alpha(th.palette.common.black, th.palette.mode === "dark" ? 0.55 : 0.14)}`,
        }}
      >
        {!isNarrow && (
          <ThemeProvider theme={shellTheme}>
            <Box sx={{
              position: "relative", overflow: "hidden", background: shellBg, color: "#fff",
              px: 6, py: 5.5, display: "flex", flexDirection: "column", justifyContent: "space-between", gap: 4,
            }}>
              {/* Geometría de fondo: dos halos muy suaves que rompen el plano sin competir con
                  el texto. Decoración pura, por eso queda fuera del flujo y sin rol. */}
              <Box aria-hidden sx={{
                position: "absolute", top: -110, right: -90, width: 380, height: 380, borderRadius: "50%",
                background: `radial-gradient(circle, ${alpha("#fff", 0.13)} 0%, ${alpha("#fff", 0)} 68%)`,
              }} />
              <Box aria-hidden sx={{
                position: "absolute", bottom: -140, left: -120, width: 420, height: 420, borderRadius: "50%",
                background: `radial-gradient(circle, ${alpha("#fff", 0.09)} 0%, ${alpha("#fff", 0)} 70%)`,
              }} />

              <Box sx={{ position: "relative" }}>
                <EbimMark size={34} color="#fff" animated />
                <Typography sx={{
                  mt: 4, fontSize: 40, fontWeight: 800, letterSpacing: "-0.03em", lineHeight: 1, color: "#fff",
                }}>
                  <Box component="span" sx={{ color: accentSoft }}>e</Box>TMS
                </Typography>
                <Typography sx={{
                  mt: 1.5, textTransform: "uppercase", letterSpacing: ".16em", fontSize: 10.5,
                  fontWeight: 800, color: alpha("#fff", 0.6),
                }}>
                  {t("Gestión de transporte")}
                </Typography>
              </Box>

              <Box sx={{ position: "relative" }}>
                <Typography sx={{ fontSize: 27, fontWeight: 800, lineHeight: 1.22, color: "#fff", mb: 1.75, maxWidth: 350 }}>
                  {t("Cada viaje sale con la carga que cabe.")}
                </Typography>
                <Typography sx={{ fontSize: T.bodyStrong, lineHeight: 1.55, color: alpha("#fff", 0.78), mb: 3.5, maxWidth: 400 }}>
                  {t("Del pedido al viaje sin planillas intermedias: capacidad, rutas y flota en un solo sistema.")}
                </Typography>

                <Box component="ul" sx={{ listStyle: "none", p: 0, m: 0, display: "grid", gap: 2.25 }}>
                  {FEATURES.map((feature) => (
                    <Box component="li" key={feature.title} sx={{ display: "flex", gap: 1.75, alignItems: "flex-start" }}>
                      <Box sx={{
                        width: 36, height: 36, borderRadius: `${R.md}px`, flexShrink: 0, display: "grid", placeItems: "center",
                        bgcolor: alpha("#fff", 0.15), color: "#fff", "& svg": { fontSize: 19 },
                      }}>
                        {feature.icon}
                      </Box>
                      <Box>
                        <Typography sx={{ fontWeight: 800, fontSize: T.bodyStrong, color: "#fff", lineHeight: 1.35 }}>
                          {t(feature.title)}
                        </Typography>
                        <Typography sx={{ fontSize: T.body, color: alpha("#fff", 0.7), lineHeight: 1.45 }}>
                          {t(feature.text)}
                        </Typography>
                      </Box>
                    </Box>
                  ))}
                </Box>
              </Box>

              <Box sx={{ position: "relative" }}>
                <Typography sx={{
                  display: "flex", alignItems: "center", gap: 0.85,
                  fontSize: T.label + 0.5, fontWeight: 600, color: alpha("#fff", 0.72),
                }}>
                  <ShieldRounded sx={{ fontSize: 15 }} />
                  {t("Conexión cifrada y datos aislados por compañía.")}
                </Typography>
                <Typography sx={{
                  mt: 0.85, fontSize: T.label, color: alpha("#fff", 0.42), letterSpacing: ".08em",
                }}>
                  {t("Suite EBIM · TMS · EWM")}
                </Typography>
              </Box>
            </Box>
          </ThemeProvider>
        )}

        <Box component="main" sx={{
          position: "relative", overflow: "hidden", bgcolor: "background.paper",
          px: { xs: 3, sm: 6 }, py: { xs: 4, sm: 6 },
          display: "flex", flexDirection: "column", justifyContent: "center", alignItems: "center",
        }}>
          {/* La misma geometría de la mitad izquierda, aquí en trazo y en el acento del tema.
              Sin ella el formulario flota sobre un rectángulo blanco vacío. */}
          <Box aria-hidden sx={{
            position: "absolute", top: -150, right: -120, width: 300, height: 300, borderRadius: "50%",
            border: "1.5px solid", borderColor: (th) => alpha(th.palette.primary.main, 0.13),
          }} />
          <Box aria-hidden sx={{
            position: "absolute", top: -60, right: -150, width: 230, height: 230, borderRadius: "50%",
            bgcolor: (th) => alpha(th.palette.primary.main, 0.05),
          }} />
          <Box aria-hidden sx={{
            position: "absolute", bottom: -95, right: -85, width: 210, height: 210, borderRadius: `${R.xl + 16}px`,
            transform: "rotate(-14deg)", bgcolor: (th) => alpha(th.palette.primary.main, 0.05),
          }} />

          {/* Mobiliario de la visita, no del formulario: el idioma y la apariencia se quedan en
              la esquina para no romper el eje central de la composición. */}
          <Box sx={{ position: "absolute", top: 16, right: 16, display: "flex", alignItems: "center", gap: 1, zIndex: 2 }}>
            <ToggleButtonGroup
              size="small"
              exclusive
              value={lang}
              onChange={(_, next: string | null) => next && setLang(next as "es" | "en")}
              aria-label={t("Idioma")}
              sx={{
                bgcolor: "background.default", borderRadius: R.pill, p: 0.375,
                "& .MuiToggleButton-root": {
                  border: 0, borderRadius: R.pill, px: 1.35, py: 0.2, minHeight: 0,
                  fontSize: T.micro, fontWeight: 800, color: "text.secondary",
                },
                "& .Mui-selected": { bgcolor: "background.paper", color: "text.primary" },
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
              sx={{ bgcolor: "background.default" }}
            >
              {mode === "dark" ? <LightModeRounded fontSize="small" /> : <DarkModeRounded fontSize="small" />}
            </IconButton>
          </Box>

          <Box sx={{ position: "relative", width: "100%", maxWidth: 372 }}>
            {isNarrow && (
              <Box sx={{ display: "flex", justifyContent: "center", mb: 3 }}>
                <EbimMark size={30} color={theme.palette.primary.main} />
              </Box>
            )}

            <Typography sx={{
              textAlign: "center", fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", mb: 1,
            }}>
              {t("Bienvenido")}
            </Typography>
            <Typography sx={{ textAlign: "center", fontSize: T.body, color: "text.secondary", mb: 4 }}>
              {t("Ingresa tus credenciales para continuar")}
            </Typography>

            {formError && <Alert severity="error" sx={{ mb: 2.5, borderRadius: `${R.md}px` }}>{formError}</Alert>}

            <Box component="form" onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
              <LabelledField id="email" label={t("Correo electrónico")}>
                <TextField
                  id="email"
                  type="email"
                  placeholder="nombre@empresa.com"
                  autoComplete="username"
                  autoFocus
                  required
                  fullWidth
                  error={Boolean(errors.email)}
                  helperText={errors.email?.message}
                  sx={fieldSx}
                  {...register("email", { required: t("El correo electrónico es obligatorio") })}
                />
              </LabelledField>

              <LabelledField id="password" label={t("Contraseña")}>
                <TextField
                  id="password"
                  type={showPassword ? "text" : "password"}
                  autoComplete="current-password"
                  required
                  fullWidth
                  error={Boolean(errors.password)}
                  helperText={errors.password?.message}
                  sx={fieldSx}
                  slotProps={{
                    input: {
                      endAdornment: (
                        <InputAdornment position="end">
                          <IconButton
                            onClick={() => setShowPassword((visible) => !visible)}
                            aria-label={showPassword ? t("Ocultar contraseña") : t("Mostrar contraseña")}
                            aria-pressed={showPassword}
                            edge="end"
                            size="small"
                          >
                            {showPassword ? <VisibilityOffRounded fontSize="small" /> : <VisibilityRounded fontSize="small" />}
                          </IconButton>
                        </InputAdornment>
                      ),
                    },
                  }}
                  {...register("password", { required: t("La contraseña es obligatoria") })}
                />
              </LabelledField>

              <Button
                type="submit"
                variant="contained"
                fullWidth
                disableElevation
                disabled={isSubmitting}
                sx={{ mt: 1.5, py: 1.6, borderRadius: `${R.md}px`, fontSize: T.bodyStrong, fontWeight: 800 }}
              >
                {isSubmitting ? t("Ingresando...") : t("Ingresar")}
              </Button>
            </Box>

            <Typography sx={{ textAlign: "center", fontSize: T.body, color: "text.secondary", mt: 3 }}>
              {t("¿Sin acceso? Pídeselo al administrador de tu compañía.")}
            </Typography>

            {/* El lockup cierra el panel como lo hace el acceso del resto de la suite: después de
                las acciones, discreto, y solo como firma. */}
            {/*
              Sin `opacity` en el contenedor y sin `text.disabled` en la firma (JOB 26).
              Los dos se multiplicaban: `text.disabled` al 60% de opacidad daba **1.72:1** contra
              los 4.5:1 exigidos, y axe en Chromium lo midió. `text.disabled` es un color para
              controles apagados, no para texto que se lee; y una opacidad sobre un contenedor
              atenúa lo que hay dentro sin cambiar ningún color declarado, así que ninguna revisión
              de la paleta encuentra el fallo — sólo medirlo ya renderizado.

              Discreto se consigue con tamaño y espaciado, que es lo que queda.
            */}
            <Box sx={{
              display: "flex", alignItems: "center", justifyContent: "center", gap: 1,
              mt: 4, pt: 3, borderTop: 1, borderColor: "divider",
            }}>
              <EbimMark size={19} color={theme.palette.text.secondary} />
              <Typography sx={{ fontSize: T.bodyStrong, fontWeight: 800 }}>eTMS</Typography>
              <Typography sx={{ fontSize: T.micro, letterSpacing: ".14em", fontWeight: 700, color: "text.secondary" }}>
                BY EBIM
              </Typography>
            </Box>
          </Box>
        </Box>
      </Paper>
    </Box>
  );
}
