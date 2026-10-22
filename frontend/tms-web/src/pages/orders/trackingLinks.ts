import { useCompany } from "../../shared/company/CompanyContext";

/** El permiso propio de los enlaces de seguimiento (ADR-029); el backend exige además `orders.order:read`. */
export const TRACKING_LINK_PERMISSION = "orders.tracking_link:manage";

/** ¿Se ofrece «Compartir seguimiento»? Sólo a quien puede ver el pedido y publicar su seguimiento. */
export function useCanShareTracking(): boolean {
  const { hasPermission } = useCompany();
  return hasPermission("orders.order:read") && hasPermission(TRACKING_LINK_PERMISSION);
}

/**
 * El mensaje que se abre en WhatsApp. En castellano siempre: es para el cliente, no para quien lo envía,
 * y la página que abre tiene su propio selector de idioma.
 */
export function trackingMessage(orderNumber: string, companyName: string | null | undefined, url: string): string {
  const from = companyName ? ` de ${companyName}` : "";
  return `Hola. Puede seguir su pedido ${orderNumber}${from} en este enlace: ${url}`;
}
