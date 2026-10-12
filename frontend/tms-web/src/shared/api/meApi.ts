import { apiRequest } from "./httpClient";

export interface OrganizationView {
  id: string;
  code: string;
  name: string;
}

/**
 * Una empresa en la que quien llama puede operar, reflejo del `CompanyAccessView` del backend.
 * `permissions` y `capabilities` son insumo de UX, no concesiones: esconder o mostrar una
 * entrada de menú a partir de ellos no cambia nada de lo que el backend permite, porque cada
 * endpoint vuelve a comprobar el permiso fino contra la empresa que llega en `X-Company-Id`.
 */
export interface CompanyAccessView {
  id: string;
  code: string;
  name: string;
  timeZone: string;
  organization: OrganizationView;
  permissions: string[];
  capabilities: string[];
  /**
   * ADR-018 §1: el transportista de una membresía de transportista, o `null` para una membresía
   * de oficina. Opcional en el tipo porque un backend anterior a V57 no lo envía, y su ausencia
   * significa exactamente lo mismo que `null`.
   */
  carrierId?: string | null;
  carrierName?: string | null;
}

/** Las capabilities que el frontend consulta por nombre. El resto viajan como texto. */
export const CAPABILITY = {
  /** ADR-018: el portal móvil de transportistas (`/portal`). */
  CARRIER_PORTAL: "CARRIER_PORTAL",
  /** ADR-018: el monitor de entrega de documentos de la oficina. */
  DOCUMENT_DELIVERY_VIEW: "DOCUMENT_DELIVERY_VIEW",
  /** ADR-018: confirmar que el cargo firmado volvió a la oficina. */
  DOCUMENT_DELIVERY_MANAGE: "DOCUMENT_DELIVERY_MANAGE",
} as const;

/**
 * ¿Opera quien llama en esta empresa como transportista? Una membresía de transportista solo ve
 * el portal: la aplicación de oficina la reenvía a `/portal` y no le enseña su menú. Es
 * orientación de UX; lo que de verdad la limita son los permisos de sus roles `CARRIER`.
 */
export function isCarrierScoped(company: Pick<CompanyAccessView, "carrierId"> | null | undefined): boolean {
  return company?.carrierId !== undefined && company.carrierId !== null && company.carrierId !== "";
}

export interface UserView {
  id: string;
  email: string;
  fullName: string;
}

export interface MeView {
  user: UserView;
  companies: CompanyAccessView[];
}

/** Todo lo necesario justo después del login: perfil, empresas seleccionables y los
 * permisos/capabilities por empresa, en una sola llamada. */
export function fetchMe(signal?: AbortSignal): Promise<MeView> {
  return apiRequest<MeView>("/me", { signal });
}
