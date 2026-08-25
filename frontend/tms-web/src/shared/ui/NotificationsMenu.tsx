import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Badge, Box, Button, CircularProgress, Divider, IconButton, List, ListItemButton,
  Menu, Tooltip, Typography,
} from "@mui/material";
import {
  NotificationsRounded, ErrorOutlineRounded, WarningAmberRounded, InfoOutlined, DoneAllRounded,
} from "@mui/icons-material";
import {
  fetchNotifications, markAllNotificationsRead, markNotificationRead,
  type NotificationSeverity, type NotificationType, type NotificationView,
} from "../api/notificationsApi";
import { useCompany } from "../company/CompanyContext";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";

/** Una frase por tipo, con los marcadores que manda el backend en `messageArgs`. Es texto de
 * presentación: el tipo y sus argumentos son el contrato, la frase es nuestra. */
const MESSAGE: Record<NotificationType, { title: string; text: string }> = {
  TRIP_DELAYED: { title: "Viaje retrasado", text: "El viaje {{trip}} salió con retraso." },
  EXCEPTION_OPENED: { title: "Incidencia abierta", text: "Se abrió una incidencia en el viaje {{trip}}." },
  TENDER_REJECTED: { title: "Oferta rechazada", text: "{{carrier}} rechazó el viaje {{trip}}." },
  TENDER_EXPIRED: { title: "Oferta vencida", text: "La oferta del viaje {{trip}} venció sin respuesta." },
  DRIVER_LICENSE_EXPIRING: { title: "Licencia por vencer", text: "La licencia de {{driver}} vence pronto." },
  TRIP_COMPLETED: { title: "Viaje completado", text: "El viaje {{trip}} se cerró." },
  DELIVERY_FAILED: { title: "Entrega fallida", text: "Una entrega del viaje {{trip}} no se pudo completar." },
};

const SEVERITY_ICON: Record<NotificationSeverity, typeof InfoOutlined> = {
  INFO: InfoOutlined,
  WARNING: WarningAmberRounded,
  CRITICAL: ErrorOutlineRounded,
};

const SEVERITY_COLOR: Record<NotificationSeverity, string> = {
  INFO: "info.main",
  WARNING: "warning.main",
  CRITICAL: "error.main",
};

/** Cuánto espera la campana antes de volver a preguntar. Un minuto: una alerta que llega un
 * minuto tarde sigue siendo útil, y sesenta peticiones por hora y pestaña no lo serían. */
const POLL_MS = 60_000;

/**
 * La campana de la barra superior: la insignia y el panel, alimentados por una sola petición.
 *
 * La lectura marca en nombre de la *empresa*, no del usuario: dos despachadores comparten una
 * insignia a propósito, porque una alerta que ya atendió uno no debería seguir persiguiendo al
 * otro. El backend responde con el feed refrescado, así que el conteo nunca queda obsoleto.
 */
export function NotificationsMenu({ iconSx }: { iconSx?: object }) {
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [anchor, setAnchor] = useState<null | HTMLElement>(null);
  const [busy, setBusy] = useState(false);

  const feed = useQuery({
    queryKey: ["notifications", companyId],
    queryFn: ({ signal }) => fetchNotifications(companyId, signal),
    enabled: companyId !== "",
    refetchInterval: POLL_MS,
    staleTime: POLL_MS / 2,
  });

  const unread = feed.data?.unreadCount ?? 0;
  const items = feed.data?.notifications ?? [];

  async function open(notification: NotificationView) {
    setAnchor(null);
    if (notification.readAt === null) {
      try {
        const refreshed = await markNotificationRead(companyId, notification.id);
        queryClient.setQueryData(["notifications", companyId], refreshed);
      } catch {
        // Marcar como leída es una comodidad: si falla, la alerta se queda sin leer y el
        // usuario ya está mirando lo que le importaba, que es el viaje.
      }
    }
    if (notification.entityType === "TRIP") navigate(`/trips/${notification.entityId}`);
    else if (notification.entityType === "DRIVER") navigate("/fleet/drivers");
  }

  async function readAll() {
    setBusy(true);
    try {
      const refreshed = await markAllNotificationsRead(companyId);
      queryClient.setQueryData(["notifications", companyId], refreshed);
    } catch {
      /* la insignia se recalcula sola en el siguiente sondeo */
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Tooltip title={t("Notificaciones")}>
        <IconButton onClick={(e) => setAnchor(e.currentTarget)} sx={iconSx} aria-label={t("Notificaciones")}>
          <Badge badgeContent={unread} color="error" max={99}>
            <NotificationsRounded />
          </Badge>
        </IconButton>
      </Tooltip>

      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
        transformOrigin={{ vertical: "top", horizontal: "right" }}
        slotProps={{ paper: { sx: { mt: 1, width: 380, maxWidth: "100vw", borderRadius: 2.5, overflow: "hidden" } } }}
      >
        <Box sx={{ px: 2, py: 1.25, display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
          <Typography variant="subtitle1">{t("Notificaciones")}</Typography>
          {unread > 0 && (
            <Button size="small" onClick={readAll} disabled={busy} startIcon={<DoneAllRounded />} sx={{ minHeight: 0 }}>
              {t("Marcar todo como leído")}
            </Button>
          )}
        </Box>
        <Divider />

        {feed.isPending ? (
          <Box sx={{ display: "grid", placeItems: "center", py: 4 }}><CircularProgress size={22} /></Box>
        ) : items.length === 0 ? (
          <Box sx={{ py: 4, textAlign: "center" }}>
            <Typography variant="body2" color="text.secondary">{t("Sin notificaciones")}</Typography>
          </Box>
        ) : (
          <List sx={{ py: 0, maxHeight: 420, overflowY: "auto" }}>
            {items.map((n) => {
              const Icon = SEVERITY_ICON[n.severity];
              const copy = MESSAGE[n.type];
              const args = { ...n.messageArgs, trip: n.entityLabel ?? n.entityId } as Record<string, string | number>;
              return (
                <ListItemButton
                  key={n.id}
                  onClick={() => open(n)}
                  sx={{
                    alignItems: "flex-start", gap: 1.25, py: 1.25,
                    borderLeft: "3px solid",
                    borderLeftColor: n.readAt === null ? SEVERITY_COLOR[n.severity] : "transparent",
                    bgcolor: n.readAt === null ? "action.hover" : "transparent",
                  }}
                >
                  <Icon sx={{ fontSize: 19, mt: 0.25, color: SEVERITY_COLOR[n.severity], flexShrink: 0 }} />
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="body2" sx={{ fontWeight: n.readAt === null ? 800 : 600, lineHeight: 1.3 }}>
                      {t(copy.title)}
                    </Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.35 }}>
                      {t(copy.text, args)}
                    </Typography>
                    <Typography variant="caption" color="text.disabled">{fmtDateTime(n.occurredAt)}</Typography>
                  </Box>
                </ListItemButton>
              );
            })}
          </List>
        )}
      </Menu>
    </>
  );
}
