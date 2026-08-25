import { apiRequest } from "./httpClient";

/**
 * Refleja el `NotificationType` del backend (migración V32).
 *
 * Cada valor es además una clave de presentación: la API manda un tipo y unos argumentos en
 * lugar de una frase, porque un mensaje ya redactado llegaría en el idioma que al servidor le
 * apeteciera — y seguiría en él después de que el operador cambiara de idioma.
 */
export const NOTIFICATION_TYPES = [
  "TRIP_DELAYED",
  "EXCEPTION_OPENED",
  "TENDER_REJECTED",
  "TENDER_EXPIRED",
  "DRIVER_LICENSE_EXPIRING",
  "TRIP_COMPLETED",
  "DELIVERY_FAILED",
] as const;
export type NotificationType = (typeof NOTIFICATION_TYPES)[number];

/** Refleja `NotificationSeverity`. Fija por tipo, nunca la elige quien levanta la alerta. */
export const NOTIFICATION_SEVERITIES = ["INFO", "WARNING", "CRITICAL"] as const;
export type NotificationSeverity = (typeof NOTIFICATION_SEVERITIES)[number];

/** Refleja `NotificationEntityType` — de qué va la alerta, y a dónde lleva. */
export const NOTIFICATION_ENTITY_TYPES = ["TRIP", "DRIVER"] as const;
export type NotificationEntityType = (typeof NOTIFICATION_ENTITY_TYPES)[number];

/**
 * Refleja el `NotificationView` del backend.
 *
 * `messageArgs` es deliberadamente laxo: es la bolsa de marcadores de una frase, su forma
 * cambia por tipo, y tiparlo por tipo pondría una segunda copia del contrato de mensajes del
 * backend en TypeScript que podría acabar discrepando.
 */
export interface NotificationView {
  id: string;
  type: NotificationType;
  severity: NotificationSeverity;
  entityType: NotificationEntityType;
  entityId: string;
  entityLabel: string | null;
  messageArgs: Record<string, string | number | null>;
  occurredAt: string;
  readAt: string | null;
  resolvedAt: string | null;
}

/**
 * Refleja el `NotificationFeedView` del backend.
 *
 * `unreadCount` cuenta todo el histórico, no `notifications.length` — la lista está topada y la
 * insignia no, así que una mesa que dejó apilarse cien alertas lo dice.
 */
export interface NotificationFeedView {
  unreadCount: number;
  notifications: NotificationView[];
}

/**
 * La insignia y el panel en una sola petición.
 *
 * No hace falta permiso para llamar: el backend responde con las alertas a las que esta cuenta
 * tiene derecho, que para una cuenta sin ninguno de los tres permisos relevantes es una lista
 * vacía en vez de un 403. La campana es un control permanente, así que tiene que pintarse para
 * todo el mundo.
 */
export function fetchNotifications(companyId: string, signal?: AbortSignal): Promise<NotificationFeedView> {
  return apiRequest<NotificationFeedView>("/notifications", { companyId, signal });
}

/**
 * Da por vista una alerta en nombre de la empresa, no del usuario. Dos despachadores comparten
 * una insignia a propósito.
 *
 * Responde con el feed refrescado en lugar de con la alerta, para que la insignia no pueda
 * pintar un conteo obsoleto ni un frame.
 */
export function markNotificationRead(companyId: string, notificationId: string): Promise<NotificationFeedView> {
  return apiRequest<NotificationFeedView>(`/notifications/${notificationId}/read`, { method: "POST", companyId });
}

/** Limpia la insignia sobre todas las alertas que esta cuenta tiene derecho a ver. */
export function markAllNotificationsRead(companyId: string): Promise<NotificationFeedView> {
  return apiRequest<NotificationFeedView>("/notifications/read-all", { method: "POST", companyId });
}
