import { appEnv } from "../config/env";
import { ApiError, apiRequest, type ProblemDetails } from "./httpClient";

/**
 * Enlaces de seguimiento para el cliente (migración V74, ADR-029).
 *
 * Dos superficies que no se mezclan: la de oficina (`/orders/{id}/tracking-links`, con sesión,
 * `X-Company-Id` y `orders.tracking_link:manage`) y la pública (`/public/tracking/{token}`), que se
 * llama **sin** `Authorization`: el token del enlace es la única credencial, y mandar además la
 * sesión de quien lo abre —un empleado que prueba el enlace— no debe cambiar lo que se ve.
 */

/** Cómo el enlace muestra la placa: los tres últimos caracteres, o nada. Espejo de `TrackingPlateDisplay`. */
export type TrackingPlateDisplay = "MASKED" | "HIDDEN";
export const TRACKING_PLATE_DISPLAYS: readonly TrackingPlateDisplay[] = ["MASKED", "HIDDEN"];

export type TrackingLinkState = "ACTIVE" | "EXPIRED" | "REVOKED";

/** Un enlace visto desde la oficina. Nunca el token: se muestra una vez, al generarlo. */
export interface TrackingLinkView {
  id: string;
  state: TrackingLinkState;
  createdAt: string;
  createdBy: string;
  /** La caducidad efectiva: la menor entre el tope y 7 días después de cerrarse el pedido. */
  expiresAt: string;
  revokedAt: string | null;
  revokeReason: string | null;
  lastViewedAt: string | null;
  viewCount: number;
  version: number;
}

/** La única respuesta que trae el token. `path` es `/t/<token>`; la URL completa la arma la oficina. */
export interface IssuedTrackingLinkView {
  link: TrackingLinkView;
  token: string;
  path: string;
}

export function fetchTrackingLinks(companyId: string, orderId: string, signal?: AbortSignal): Promise<TrackingLinkView[]> {
  return apiRequest<TrackingLinkView[]>(`/orders/${encodeURIComponent(orderId)}/tracking-links`, { companyId, signal });
}

export function issueTrackingLink(companyId: string, orderId: string): Promise<IssuedTrackingLinkView> {
  return apiRequest<IssuedTrackingLinkView>(`/orders/${encodeURIComponent(orderId)}/tracking-links`, {
    method: "POST", companyId,
  });
}

export function revokeTrackingLink(
  companyId: string, orderId: string, linkId: string, reason: string, version?: number,
): Promise<TrackingLinkView> {
  return apiRequest<TrackingLinkView>(
    `/orders/${encodeURIComponent(orderId)}/tracking-links/${encodeURIComponent(linkId)}/revoke`,
    { method: "POST", companyId, body: { reason, version } },
  );
}

/** La URL que se comparte: el origen de esta misma aplicación más `/t/<token>`. */
export function publicTrackingUrl(path: string, origin: string = window.location.origin): string {
  return `${origin.replace(/\/+$/, "")}${path}`;
}

/** `https://wa.me/?text=…`: abre WhatsApp con el mensaje escrito y sin destinatario. No se guarda ningún teléfono. */
export function whatsAppShareUrl(message: string): string {
  return `https://wa.me/?text=${encodeURIComponent(message)}`;
}

// ── La vista pública ───────────────────────────────────────────────────────────────────────────

export type CustomerTrackingStatus =
  | "RECEIVED" | "PLANNED" | "IN_TRANSIT" | "ARRIVED" | "DELIVERED" | "PARTIALLY_DELIVERED"
  | "REJECTED" | "NOT_DELIVERED" | "CANCELLED";

export type TrackingStepCode = "RECEIVED" | "PLANNED" | "IN_TRANSIT" | "ARRIVED" | "OUTCOME";

export interface PublicTrackingStep {
  code: TrackingStepCode;
  state: "DONE" | "PENDING";
  at: string | null;
  outcome: CustomerTrackingStatus | null;
}

/** Espejo de `PublicTrackingView`: todo lo que el enlace publica, y nada más. */
export interface PublicTrackingView {
  companyName: string;
  orderNumber: string;
  reference: string | null;
  destinationName: string | null;
  status: CustomerTrackingStatus;
  timeline: PublicTrackingStep[];
  arrivalWindow: { from: string; to: string; basis: "ESTIMATE" | "SERVICE_WINDOW" } | null;
  carrierName: string | null;
  vehiclePlate: string | null;
  proofOfDelivery: { receiverName: string | null; deliveredAt: string } | null;
  timeZone: string;
  expiresAt: string;
  generatedAt: string;
}

/**
 * Lee un enlace sin sesión. `fetch` directo y no `apiRequest`: aquél pone el bearer de quien esté
 * conectado y reintenta con un refresh ante un 401, y nada de eso tiene sentido aquí.
 */
export async function fetchPublicTracking(token: string, signal?: AbortSignal): Promise<PublicTrackingView> {
  const response = await fetch(`${appEnv.apiBaseUrl}/public/tracking/${encodeURIComponent(token)}`, {
    method: "GET",
    headers: { Accept: "application/json, application/problem+json" },
    credentials: "omit",
    referrerPolicy: "no-referrer",
    cache: "no-store",
    signal,
  });
  const contentType = response.headers.get("content-type") ?? "";
  const payload: unknown = contentType.includes("json") ? await response.json().catch(() => null) : null;
  if (!response.ok) {
    throw new ApiError(response.status, (payload as ProblemDetails | null) ?? null, "", `HTTP ${response.status}`);
  }
  return payload as PublicTrackingView;
}
