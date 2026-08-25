import { Suspense, useEffect, useState, type ReactNode } from "react";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import {
  AppBar, Box, Divider, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText,
  Toolbar, Tooltip, Typography, useMediaQuery, useTheme,
} from "@mui/material";
import { ThemeProvider, lighten } from "@mui/material/styles";
import {
  MenuRounded, ChevronLeftRounded, ChevronRightRounded, LightModeRounded, DarkModeRounded,
} from "@mui/icons-material";
import { R, T, getTheme, SIDEBAR, brandSidebar, type ThemeKey } from "../../theme";
import { useColorMode } from "../../lib/colorMode";
import { t } from "../../lib/i18n";
import { useCompany } from "../company/CompanyContext";
import { LoadingState } from "./components/states";
import { EbimMark, ProductLockup } from "./EbimLogo";
import { AccountMenu } from "./AccountMenu";
import { CompanySelector } from "./CompanySelector";
import { NavSearch } from "./NavSearch";
import { NotificationsMenu } from "./NotificationsMenu";
import { NAV_SECTIONS, OVERVIEW_NAV, leafOf, sectionOf, type NavLeaf } from "./navConfig";

const DRAWER_WIDTH = 248;
const RAIL_WIDTH = 72;
const COLLAPSE_KEY = "ebim-sidebar-collapsed";

/**
 * El texto del menú lateral.
 *
 * Es el mismo valor que `palette.sidebarText`, pero va como constante: el degradado del
 * marco se pinta con un color fijo y no con el papel del tema, así que este par tiene que
 * quedar resuelto aquí y no depender del modo claro/oscuro del lienzo.
 */
const SIDEBAR_TEXT = "rgba(237,247,241,.9)";

/**
 * El armazón de la aplicación: barra lateral, barra superior y el hueco donde vive cada pantalla.
 *
 * La lateral es SIEMPRE oscura y lleva el gradiente del tema activo, como el resto de la suite
 * EBIM: el marco es identidad de producto y no cambia con el modo, que solo gobierna el lienzo.
 * La barra superior, en cambio, va sobre el papel y separada por una línea — es la disposición
 * del EWM, y hace que las migas y el buscador pertenezcan al contenido y no al marco.
 *
 * El menú se filtra por capability de la empresa activa. Esconder es solo UX: cada endpoint
 * detrás de cada pantalla vuelve a comprobar el permiso contra el `X-Company-Id` que le llegue.
 */
export function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down("md"));
  const { mode, toggle: toggleMode, themeKey, brandAccent } = useColorMode();
  const { hasCapability, status, selected, profile } = useCompany();

  // Marco premium: oscuro siempre, tintado por el tema.
  const shellTheme = getTheme("dark", themeKey, brandAccent ?? undefined);
  const shellBg = themeKey === "brand"
    ? (brandAccent ? brandSidebar(brandAccent) : SIDEBAR.forest)
    : SIDEBAR[themeKey as Exclude<ThemeKey, "brand">];

  const [drawerOpen, setDrawerOpen] = useState(!isMobile);
  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem(COLLAPSE_KEY) === "1"; } catch { return false; }
  });

  useEffect(() => {
    try { localStorage.setItem(COLLAPSE_KEY, collapsed ? "1" : "0"); } catch { /* noop */ }
  }, [collapsed]);

  // Sincroniza el drawer con el viewport: escritorio → siempre abierto (persistente); móvil →
  // cerrado por defecto. Evita que la lateral quede oculta sin forma de reabrirla al pasar de
  // móvil a escritorio, donde el botón ☰ solo colapsa y no abre.
  useEffect(() => { setDrawerOpen(!isMobile); }, [isMobile]);

  const isCollapsed = collapsed && !isMobile;
  const drawerW = isCollapsed ? RAIL_WIDTH : DRAWER_WIDTH;

  /** Una capability solo se da por concedida cuando `/me` ya respondió: antes de eso no se sabe
   * nada, y ofrecer de más es peor que ofrecer de menos durante medio segundo. */
  const allowed = (capability?: string) =>
    capability === undefined || (status === "ready" && hasCapability(capability));

  const overview = OVERVIEW_NAV.filter((leaf) => allowed(leaf.capability));
  const sections = NAV_SECTIONS
    .filter((section) => allowed(section.capability))
    .map((section) => ({ ...section, items: section.items.filter((item) => allowed(item.capability)) }))
    .filter((section) => section.items.length > 0);

  // Migas: sección › pantalla, en una sola línea. `leafOf` casa por prefijo, así que
  // `/trips/{id}` sigue diciendo "Viajes" en lugar de quedarse sin título.
  const leaf = leafOf(location.pathname);
  const currentSection = sectionOf(leaf?.to ?? location.pathname);
  const currentTitle = leaf ? t(leaf.label) : "eTMS";

  /** Los iconos de la barra superior, ahora sobre papel: neutros en reposo y con el realce
   *  de acción del tema al pasar por encima. */
  const topIconSx = {
    color: "text.secondary", borderRadius: `${R.md}px`, width: 36, height: 36,
    transition: "background-color .15s ease, color .15s ease, transform .12s ease",
    "&:hover": { bgcolor: "action.hover", color: "text.primary" },
    "&:active": { transform: "scale(0.92)" },
    "& svg": { fontSize: 19 },
  };

  /**
   * Un ítem del menú, con el icono desnudo.
   *
   * El realce del ítem activo es el fondo del propio enlace, no una pastilla de color
   * detrás del icono: con cuarenta entradas, cuarenta cuadraditos de colores distintos
   * convierten el menú en una paleta y hacen que el ítem donde uno está deje de destacar.
   */
  const renderItem = (item: NavLeaf) => {
    const active = item.to === "/"
      ? location.pathname === "/"
      : location.pathname === item.to || location.pathname.startsWith(`${item.to}/`);
    const button = (
      <ListItemButton
        key={item.to}
        onClick={() => { navigate(item.to); if (isMobile) setDrawerOpen(false); }}
        selected={active}
        sx={{
          mx: "8px", my: 0, py: "9px", minHeight: 0, borderRadius: `${R.md}px`,
          gap: isCollapsed ? 0 : "10px",
          px: isCollapsed ? 1 : "10px",
          justifyContent: isCollapsed ? "center" : "flex-start",
          color: SIDEBAR_TEXT,
          "&:hover": { bgcolor: "rgba(255,255,255,.08)" },
          "&.Mui-selected": {
            bgcolor: "rgba(255,255,255,.13)", color: "#fff",
            "&:hover": { bgcolor: "rgba(255,255,255,.18)" },
          },
          "&.Mui-focusVisible": { outline: "2px solid #fff", outlineOffset: "-2px" },
        }}
      >
        <ListItemIcon sx={{
          minWidth: 0, color: "inherit", justifyContent: "center", "& svg": { fontSize: 18 },
        }}>
          {item.icon}
        </ListItemIcon>
        {!isCollapsed && (
          <ListItemText
            primary={t(item.label)}
            slotProps={{ primary: { sx: { fontSize: T.body, fontWeight: active ? 700 : 500 } } }}
          />
        )}
      </ListItemButton>
    );
    return isCollapsed
      ? <Tooltip key={item.to} title={t(item.label)} placement="right">{button}</Tooltip>
      : button;
  };

  const sectionLabel = (text: string): ReactNode =>
    isCollapsed
      ? <Box key={text} sx={{ my: 1.25, mx: "auto", width: 22, height: "2px", borderRadius: 1, bgcolor: "rgba(255,255,255,0.16)" }} />
      : (
        <Typography
          key={text}
          sx={{
            px: "16px", pt: "12px", pb: "6px", display: "block", textTransform: "uppercase",
            letterSpacing: ".12em", fontWeight: 800, fontSize: T.micro, opacity: 0.45,
          }}
        >
          {text}
        </Typography>
      );

  const drawer = (
    <ThemeProvider theme={shellTheme}>
      <Box sx={{
        display: "flex", flexDirection: "column", height: "100%",
        background: shellBg, color: SIDEBAR_TEXT,
      }}>
        {/* Cabecera: la marca y, debajo, la organización a la que se ha entrado. Saber en qué
            organización se está es contexto de lectura de todo lo demás, no un dato de perfil. */}
        <Box sx={{
          display: "flex", alignItems: "center", justifyContent: isCollapsed ? "center" : "space-between",
          px: isCollapsed ? 1 : "16px", pt: "18px", pb: "10px",
        }}>
          {isCollapsed ? (
            <EbimMark size={28} color="#fff" animated />
          ) : (
            <>
              <Box sx={{ minWidth: 0 }}>
                <ProductLockup name="eTMS" color="#fff" accentColor={lighten(theme.palette.primary.main, 0.55)} animated />
                <Box sx={{
                  mt: "8px", fontSize: T.label, fontWeight: 700, opacity: 0.7,
                  whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
                }}>
                  {selected?.organization.name ?? t("Organización")}
                </Box>
              </Box>
              {isMobile && (
                <IconButton onClick={() => setDrawerOpen(false)} size="small" sx={{ color: "inherit" }}>
                  <ChevronLeftRounded />
                </IconButton>
              )}
            </>
          )}
        </Box>

        {/* Navegación. El scroll existe siempre, pero su barra arranca transparente y aparece al
            pasar por encima; para que se NOTE que la lista sigue, el pie lleva un degradado
            anclado con `background-attachment: local` — el navegador lo dibuja solo mientras
            queda contenido debajo y lo retira solo al llegar al final. */}
        <List sx={{
          flex: 1, minHeight: 0, pt: 0, pb: 2, overflowY: "auto", overflowX: "hidden",
          backgroundImage: "linear-gradient(to top, rgba(0,0,0,.28), rgba(0,0,0,0) 34px)",
          backgroundRepeat: "no-repeat",
          backgroundAttachment: "local",
          backgroundPosition: "bottom",
          backgroundSize: "100% 34px",
          scrollbarWidth: "thin",
          scrollbarColor: "transparent transparent",
          "&:hover": { scrollbarColor: "rgba(255,255,255,.28) transparent" },
          "&::-webkit-scrollbar": { width: 6 },
          "&::-webkit-scrollbar-track": { background: "transparent" },
          "&::-webkit-scrollbar-thumb": { background: "transparent", borderRadius: 999 },
          "&:hover::-webkit-scrollbar-thumb": { background: "rgba(255,255,255,.28)" },
          "&::-webkit-scrollbar-thumb:hover": { background: "rgba(255,255,255,.42)" },
        }}>
          {overview.map(renderItem)}
          {sections.map((section) => (
            <Box key={section.title} sx={{ mb: "6px" }}>
              {sectionLabel(t(section.title))}
              {section.items.map(renderItem)}
            </Box>
          ))}
        </List>

        {!isCollapsed && (
          <>
            <Divider sx={{ borderColor: "rgba(255,255,255,.1)" }} />
            <Box sx={{
              px: "16px", py: "12px", fontSize: T.micro, opacity: 0.6,
              whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
            }}>
              {selected?.name ?? profile?.email ?? ""}
            </Box>
          </>
        )}
      </Box>
    </ThemeProvider>
  );

  return (
    <Box sx={{ display: "flex", minHeight: "100vh" }}>
      <AppBar
        position="fixed"
        elevation={0}
        sx={{
          zIndex: (th) => th.zIndex.drawer + 1,
          bgcolor: "background.paper",
          color: "text.primary",
          borderBottom: "1px solid",
          borderColor: "divider",
          width: { md: drawerOpen ? `calc(100% - ${drawerW}px)` : "100%" },
          ml: { md: drawerOpen ? `${drawerW}px` : 0 },
          transition: "width 0.2s, margin-left 0.2s",
        }}
      >
        <Toolbar sx={{ minHeight: { xs: 58, md: 60 }, px: { xs: 2, md: 3 }, gap: 1 }}>
          <Tooltip title={t("Menú")}>
            <IconButton
              edge="start" sx={{ ...topIconSx, mr: 0.5 }}
              onClick={() => { if (isMobile) setDrawerOpen((v) => !v); else setCollapsed((v) => !v); }}
              aria-label={t("Menú")}
            >
              <MenuRounded />
            </IconButton>
          </Tooltip>

          {/* Migas de pan: sección › pantalla, dos niveles y no más. Van en un `<nav>` con
              nombre — sin él son una fila de texto suelto que un lector de pantalla no puede
              distinguir del título de la pantalla ni saltar. */}
          <Box
            component="nav"
            aria-label={t("Ubicación")}
            sx={{
              display: "flex", alignItems: "center", gap: "6px", minWidth: 0,
              fontSize: T.body, color: "text.secondary", mr: 1,
            }}
          >
            {currentSection && !isMobile && (
              <>
                <Box component="span" sx={{ whiteSpace: "nowrap" }}>{t(currentSection)}</Box>
                <ChevronRightRounded sx={{ fontSize: 14 }} aria-hidden />
              </>
            )}
            <Box component="span" aria-current="page" sx={{
              color: "text.primary", fontWeight: 700, whiteSpace: "nowrap",
              overflow: "hidden", textOverflow: "ellipsis",
            }}>
              {currentTitle}
            </Box>
          </Box>

          {/* Centro: buscador de pantallas */}
          <Box sx={{ flex: 1, display: "flex", justifyContent: "center", minWidth: 0 }}>
            <NavSearch />
          </Box>

          {/* Derecha: contexto y controles */}
          <Box sx={{ display: "flex", alignItems: "center", justifyContent: "flex-end", gap: 0.4, flexShrink: 0 }}>
            {!isMobile && <CompanySelector />}

            <Tooltip title={mode === "dark" ? t("Modo claro") : t("Modo oscuro")}>
              <IconButton onClick={toggleMode} sx={topIconSx} aria-label={t("Apariencia")}>
                {mode === "dark" ? <LightModeRounded /> : <DarkModeRounded />}
              </IconButton>
            </Tooltip>

            <NotificationsMenu iconSx={topIconSx} />

            <Divider orientation="vertical" flexItem sx={{ mx: 1, my: 1.5 }} />

            <AccountMenu />
          </Box>
        </Toolbar>
      </AppBar>

      {/* Barra lateral: temporal en móvil, permanente en escritorio */}
      <Box component="nav" sx={{ width: { md: drawerOpen ? drawerW : 0 }, flexShrink: { md: 0 } }}>
        <Drawer
          variant={isMobile ? "temporary" : "persistent"}
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          ModalProps={{ keepMounted: true }}
          sx={{
            /**
             * En móvil el cajón es modal y va SOBRE la barra superior; en escritorio es
             * persistente y se queda debajo.
             *
             * No es la misma pieza haciendo dos cosas por capricho: el persistente comparte el
             * espacio con la barra —que se desplaza con `ml` para no pisarlo— mientras que el
             * modal la tapa por definición. Con el apilado de fábrica (`zIndex.drawer`, un punto
             * por debajo de la barra), el cajón de móvil perdía sus primeros sesenta píxeles: el
             * logotipo y el botón de cerrar quedaban detrás de una barra que además seguía sin
             * atenuar sobre un fondo ya oscurecido.
             *
             * Se cuenta desde `zIndex.drawer` y no desde `zIndex.appBar`: ese token vale 1100 en
             * MUI, cien por debajo del cajón, y la barra de aquí arriba no lo usa — se sube a
             * `drawer + 1` unas líneas más arriba. Los dos valores tienen que moverse juntos.
             */
            ...(isMobile ? { zIndex: (th) => th.zIndex.drawer + 2 } : {}),
            "& .MuiDrawer-paper": {
              width: drawerW, boxSizing: "border-box", border: "none",
              transition: "width 0.2s", overflowX: "hidden",
            },
          }}
        >
          {drawer}
        </Drawer>
      </Box>

      <Box
        component="main"
        sx={{
          flexGrow: 1, minWidth: 0, bgcolor: "background.default",
          p: { xs: 2, md: 3 },
          width: { md: drawerOpen ? `calc(100% - ${drawerW}px)` : "100%" },
          transition: "width 0.2s",
        }}
      >
        <Toolbar sx={{ minHeight: { xs: 58, md: 60 } }} />
        {/**
         * El contenido tiene un ancho máximo, y se centra al superarlo.
         *
         * Sin él, en un monitor ancho —o simplemente con la lateral plegada— cada pantalla se
         * estira hasta el borde: cuatro tarjetas de KPI de 440 px con el número flotando a un
         * palmo de su etiqueta, filas de accesos que dejan media rejilla vacía y pares
         * etiqueta/valor separados por trescientos píxeles de nada. Nada de eso es un defecto de
         * las pantallas; es que ninguna declaraba hasta dónde tenía sentido crecer.
         *
         * 1600 px deja sitio de sobra a una tabla de doce columnas y evita las líneas de texto
         * de un metro, que es donde el ojo pierde el renglón al volver.
         */}
        <Box sx={{ maxWidth: 1600, mx: "auto", width: "100%" }}>
          {/* El único límite de Suspense de la app: el armazón se queda en pantalla mientras se
              descarga el trozo de una pantalla, en lugar de parpadear entero. */}
          <Suspense fallback={<LoadingState minHeight="50vh" />}>
            <Outlet />
          </Suspense>
        </Box>
      </Box>
    </Box>
  );
}
