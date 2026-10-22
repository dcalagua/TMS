import { apiRequest } from "./httpClient";

export interface OrganizationView {
  id: string;
  code: string;
  name: string;
}

/**
 * El estado operativo de la organización de una empresa, tal como EBIM MasterAdmin lo fijó por
 * última vez (`lifecycle.v1`, ADR-021). Es el insumo del aviso «Cuenta suspendida» y nada más: con
 * `SUSPENDED` el backend rechaza toda escritura con `423 tenant-suspended` muestre lo que muestre
 * la pantalla, y la lectura sigue disponible.
 */
export interface OperationalStatusView {
  state: "ACTIVE" | "SUSPENDED";
  /** Solo con `SUSPENDED`. */
  reason: "NON_PAYMENT" | "MANUAL" | "TERMINATED" | null;
  appliedAt: string | null;
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
  /**
   * ADR-021: el estado operativo de su organización. Opcional porque un backend anterior a V58 no
   * lo envía; su ausencia equivale a `ACTIVE`.
   */
  operationalStatus?: OperationalStatusView | null;
}

/** ¿Está suspendida la operación de esta empresa? Solo UX: el servidor es la autoridad. */
export function isOperationSuspended(
  company: Pick<CompanyAccessView, "operationalStatus"> | null | undefined,
): boolean {
  return company?.operationalStatus?.state === "SUSPENDED";
}

/** Las capabilities que el frontend consulta por nombre. El resto viajan como texto. */
export const CAPABILITY = {
  /** ADR-018: el portal móvil de transportistas (`/portal`). */
  CARRIER_PORTAL: "CARRIER_PORTAL",
  /** ADR-018: el monitor de entrega de documentos de la oficina. */
  DOCUMENT_DELIVERY_VIEW: "DOCUMENT_DELIVERY_VIEW",
  /** ADR-018: confirmar que el cargo firmado volvió a la oficina. */
  DOCUMENT_DELIVERY_MANAGE: "DOCUMENT_DELIVERY_MANAGE",
  /** Auditoría de flete: leer facturas, comparaciones y políticas de tolerancia. */
  SETTLEMENT_VIEW: "SETTLEMENT_VIEW",
  /** Registrar y comparar facturas, resolver diferencias y mantener las tolerancias. */
  SETTLEMENT_MANAGE: "SETTLEMENT_MANAGE",
  /** Aprobar o rechazar: deliberadamente separado de quien registra la factura. */
  SETTLEMENT_APPROVE: "SETTLEMENT_APPROVE",
  /** Exportar a contabilidad lo aprobado. */
  SETTLEMENT_EXPORT: "SETTLEMENT_EXPORT",
  /** Provisiones de flete (V65): ver la obligación abierta, su antigüedad y los viajes sin provisión. */
  ACCRUAL_VIEW: "ACCRUAL_VIEW",
  /** Exportar el asiento de provisiones de un período para el ERP. */
  ACCRUAL_EXPORT: "ACCRUAL_EXPORT",
  /** Revertir o cerrar provisiones, provisionar ahora y mantener las cuentas contables. */
  ACCRUAL_MANAGE: "ACCRUAL_MANAGE",
  /** Costo para servir (V68, ADR-026): el reporte por cliente, destino y ruta. */
  COST_TO_SERVE_VIEW: "COST_TO_SERVE_VIEW",
  /** Recalcular el reparto del costo de un viaje (una versión nueva). */
  COST_ALLOCATION_MANAGE: "COST_ALLOCATION_MANAGE",
  /** V73: la huella de carbono por viaje y por cliente (`settlement.emission:read`). */
  EMISSIONS_VIEW: "EMISSIONS_VIEW",
  /** Cuentas por pagar (fase 65): programar, retener, liberar y proponer un pago manual. */
  PAYMENT_RECORD: "PAYMENT_RECORD",
  /** Confirmar o rechazar el pago manual que propuso **otra** persona (cuatro ojos). */
  PAYMENT_CONFIRM: "PAYMENT_CONFIRM",
  /** Portal: «Mis facturas» del transportista (`portal.invoice:read`). */
  CARRIER_INVOICES: "CARRIER_INVOICES",
  /** ADR-027: editar el régimen fiscal de la empresa (`settlement.tax:manage`). Leerlo es `SETTLEMENT_VIEW`. */
  TAX_REGIME_MANAGE: "TAX_REGIME_MANAGE",
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
