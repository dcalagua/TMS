import { Suspense, useEffect, useState } from "react";
import { Navigate, Outlet, useNavigate } from "react-router-dom";
import {
  AppBar, Box, Button, Divider, IconButton, ListItemIcon, Menu, MenuItem, Toolbar, Typography,
} from "@mui/material";
import {
  ApartmentRounded, CheckRounded, DarkModeRounded, LightModeRounded, LocalShippingRounded, LogoutRounded,
  MoreVertRounded, SystemUpdateRounded,
} from "@mui/icons-material";
import { CAPABILITY, isCarrierScoped } from "../../shared/api/meApi";
import { useAuth } from "../../shared/auth/AuthContext";
import { useCompany } from "../../shared/company/CompanyContext";
import { EmptyState, ErrorState, LoadingState } from "../../shared/ui/components";
import { useColorMode } from "../../lib/colorMode";
import { t, tp } from "../../lib/i18n";
import { applyServiceWorkerUpdate, useServiceWorkerUpdate } from "../../lib/serviceWorker";
import { confirmDialog } from "../../lib/ui";
import { usePortalOffline } from "./offline/offlineContext";
import { PortalOfflineProvider } from "./offline/PortalOfflineProvider";
import { PORTAL_BAR_HEIGHT, READABLE_INPUTS, SAFE_AREA } from "./portalUi";
import { SyncSheet, SyncStatusChip } from "./SyncStatus";

/**
 * El armazón del portal de transportistas (ADR-018 §7, ADR-019 §1): una barra con el
 * transportista, el estado de la sincronización y la cuenta, y una columna estrecha pensada
 * para el teléfono. Sin menú lateral de oficina, sin buscador de pantallas: quien entra aquí
 * tiene un solo trabajo.
 *
 * También es la puerta del portal. Lo abre quien tiene la capability `CARRIER_PORTAL` en la
 * empresa elegida; un usuario de oficina sin ella vuelve a `/`. Un usuario de transportista cuyo
 * rol no la trae se queda aquí con un aviso, y no se le manda a `/` —que lo devolvería aquí— en un
 * bucle.
 */
export function PortalLayout() {
  const { user } = useAuth();
  const { status, selected, hasCapability, errorMessage, refetch } = useCompany();

  usePhoneChrome();

  if (status === "idle" || status === "loading") {
    return <LoadingState label={t("Cargando tus compañías...")} minHeight="100vh" />;
  }
  if (status === "error") {
    return <ErrorState message={errorMessage ?? t("No se pudieron cargar tus compañías.")} onRetry={refetch} />;
  }

  const carrier = isCarrierScoped(selected);
  const allowed = hasCapability(CAPABILITY.CARRIER_PORTAL);
  if (selected && !allowed && !carrier) return <Navigate to="/" replace />;

  return (
    <PortalOfflineProvider userId={user?.id ?? null}>
      <PortalShell allowed={allowed} />
    </PortalOfflineProvider>
  );
}

/**
 * Lo que el portal cambia del documento mientras está montado, y deshace al salir:
 * - `viewport-fit=cover`, para pintar bajo la muesca y respetar `env(safe-area-inset-*)`. Solo
 *   aquí: la oficina conserva su viewport;
 * - `overscroll-behavior-y: contain`, para que tirar hacia abajo recargue la lista y no la página;
 * - y se piden ya las pantallas del portal, para que queden en la caché del service worker
 *   aunque no se hayan abierto todavía con señal.
 */
function usePhoneChrome() {
  useEffect(() => {
    const meta = document.querySelector<HTMLMetaElement>('meta[name="viewport"]');
    const previousViewport = meta?.content ?? null;
    if (meta && !/viewport-fit=/.test(meta.content)) meta.content = `${meta.content}, viewport-fit=cover`;
    const root = document.documentElement;
    const previousOverscroll = root.style.overscrollBehaviorY;
    root.style.overscrollBehaviorY = "contain";

    void import("./PortalTripPage").catch(() => undefined);
    void import("./PortalTripsPage").catch(() => undefined);

    return () => {
      if (meta && previousViewport !== null) meta.content = previousViewport;
      root.style.overscrollBehaviorY = previousOverscroll;
    };
  }, []);
}

function PortalShell({ allowed }: { allowed: boolean }) {
  const navigate = useNavigate();
  const { signOut } = useAuth();
  const { companies, selected, selectCompany, profile } = useCompany();
  const { entries, wipe } = usePortalOffline();
  const { mode, toggle } = useColorMode();
  const updateReady = useServiceWorkerUpdate();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [syncOpen, setSyncOpen] = useState(false);

  async function logout() {
    setAnchor(null);
    const unsynced = entries.length;
    const confirmed = await confirmDialog(unsynced > 0
      ? {
        title: t("¿Cerrar sesión con reportes sin enviar?"),
        text: tp(unsynced,
          "Tienes {{count}} reporte sin sincronizar; si cierras sesión se perderá.",
          "Tienes {{count}} reportes sin sincronizar; si cierras sesión se perderán."),
        confirmLabel: t("Cerrar sesión y perderlos"),
        cancelLabel: t("Seguir conectado"),
        dangerous: true,
      }
      : {
        title: t("¿Cerrar sesión?"),
        text: t("Deberás iniciar sesión nuevamente para continuar."),
        confirmLabel: t("Cerrar sesión"),
      });
    if (!confirmed) return;
    // Primero lo del teléfono: lo que este usuario leyó y lo que dejó en cola no se queda para
    // quien entre después.
    await wipe();
    await signOut();
  }

  function switchCompany(companyId: string) {
    setAnchor(null);
    selectCompany(companyId);
    // Un viaje abierto es de la empresa anterior: se vuelve a la lista. Si la nueva empresa es de
    // oficina, la guarda de arriba la manda a `/` en el siguiente render.
    navigate("/portal", { replace: true });
  }

  return (
    <Box sx={{ minHeight: "100dvh", bgcolor: "background.default", ...READABLE_INPUTS }}>
      <AppBar position="sticky" elevation={0} sx={{
        bgcolor: "background.paper", color: "text.primary", borderBottom: "1px solid", borderColor: "divider",
        pt: SAFE_AREA.top,
      }}>
        <Toolbar disableGutters sx={{
          gap: 1, minHeight: `${PORTAL_BAR_HEIGHT}px !important`, height: PORTAL_BAR_HEIGHT,
          pl: `calc(${SAFE_AREA.left} + 12px)`, pr: `calc(${SAFE_AREA.right} + 4px)`,
        }}>
          <Box aria-hidden sx={{
            width: 36, height: 36, borderRadius: "10px", flexShrink: 0, display: "grid", placeItems: "center",
            bgcolor: "primary.main", color: "primary.contrastText", "& svg": { fontSize: 21 },
          }}>
            <LocalShippingRounded />
          </Box>
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography noWrap sx={{ fontWeight: 800, fontSize: 16, lineHeight: 1.2 }}>
              {selected?.carrierName ?? t("Portal del transportista")}
            </Typography>
            <Typography noWrap color="text.secondary" sx={{ display: "block", lineHeight: 1.25, fontSize: 13 }}>
              {selected?.name ?? ""}
            </Typography>
          </Box>
          <SyncStatusChip onOpen={() => setSyncOpen(true)} />
          <IconButton onClick={(e) => setAnchor(e.currentTarget)} aria-label={t("Cuenta")} sx={{ width: 48, height: 48 }}>
            <MoreVertRounded />
          </IconButton>
          <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}
            anchorOrigin={{ vertical: "bottom", horizontal: "right" }} transformOrigin={{ vertical: "top", horizontal: "right" }}
            slotProps={{ list: { sx: { "& .MuiMenuItem-root": { minHeight: 52, fontSize: 16 } } } }}>
            <Box sx={{ px: 2, py: 1, maxWidth: 300 }}>
              <Typography noWrap sx={{ fontWeight: 700, fontSize: 15 }}>{profile?.fullName ?? ""}</Typography>
              <Typography noWrap color="text.secondary" sx={{ fontSize: 13 }}>{profile?.email ?? ""}</Typography>
            </Box>
            {companies.length > 1 && <Divider />}
            {companies.length > 1 && companies.map((company) => (
              <MenuItem key={company.id} selected={company.id === selected?.id} onClick={() => switchCompany(company.id)}>
                <ListItemIcon>{company.id === selected?.id ? <CheckRounded /> : <ApartmentRounded />}</ListItemIcon>
                {company.carrierName ? `${company.name} · ${company.carrierName}` : company.name}
              </MenuItem>
            ))}
            <Divider />
            <MenuItem onClick={() => { setAnchor(null); toggle(); }}>
              <ListItemIcon>{mode === "dark" ? <LightModeRounded /> : <DarkModeRounded />}</ListItemIcon>
              {mode === "dark" ? t("Modo claro") : t("Modo oscuro")}
            </MenuItem>
            <MenuItem onClick={() => void logout()}>
              <ListItemIcon><LogoutRounded /></ListItemIcon>
              {t("Cerrar sesión")}
            </MenuItem>
          </Menu>
        </Toolbar>
        {updateReady && (
          <Box role="status" sx={{
            display: "flex", alignItems: "center", gap: 1, px: 2, py: 1,
            bgcolor: "primary.main", color: "primary.contrastText",
          }}>
            <SystemUpdateRounded />
            <Typography sx={{ flex: 1, fontWeight: 700, fontSize: 15 }}>{t("Nueva versión disponible")}</Typography>
            <Button
              onClick={applyServiceWorkerUpdate} variant="contained" color="inherit"
              sx={{ color: "primary.main", bgcolor: "background.paper", fontWeight: 800, minHeight: 40 }}
            >
              {t("Actualizar")}
            </Button>
          </Box>
        )}
      </AppBar>

      <Box component="main" sx={{
        maxWidth: 720, mx: "auto", pt: 2,
        pl: `calc(${SAFE_AREA.left} + 16px)`, pr: `calc(${SAFE_AREA.right} + 16px)`,
        // Sitio para el botón flotante y la barra de gestos.
        pb: `calc(${SAFE_AREA.bottom} + 104px)`,
      }}>
        {companies.length === 0 || !selected ? (
          <EmptyState
            title={t("Sin acceso a compañías")}
            message={t("Tu cuenta no tiene una membresía activa en ninguna compañía. Contacta a un administrador.")}
          />
        ) : !allowed ? (
          <EmptyState
            title={t("Tu acceso no incluye el portal")}
            message={t("Tu usuario de transportista no tiene un rol con acceso a los viajes. Pide a la oficina que te asigne el rol de operador de transportista.")}
          />
        ) : (
          <Suspense fallback={<LoadingState minHeight="50vh" />}>
            <Outlet />
          </Suspense>
        )}
      </Box>

      <SyncSheet open={syncOpen} onClose={() => setSyncOpen(false)} />
    </Box>
  );
}
