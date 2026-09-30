import type { ControlTowerAdvisoryView } from "../../shared/api/controlTowerApi";
import { ENUM_LABELS } from "../../lib/enums";
import { t } from "../../lib/i18n";

/**
 * Qué dice y adónde lleva cada aviso de la torre. Fuera del componente para probarlo sin montar
 * el panel, y porque el catálogo crece en el servidor más rápido que esta pantalla.
 */

/** La etiqueta de un tipo conocido, o una genérica para uno que esta versión no conoce: el panel
 * sigue enseñando el `detail` del servidor, que es lo que de verdad explica el aviso. */
export function advisoryLabel(type: string): string {
  const known = (ENUM_LABELS.advisoryType as Record<string, string>)[type];
  return known === undefined ? t("Aviso de la operación") : t(known);
}

export function isKnownAdvisory(type: string): boolean {
  return type in ENUM_LABELS.advisoryType;
}

/**
 * Adónde se va a actuar. Una discrepancia de liquidación se resuelve en Liquidaciones; el resto,
 * en el envío. Un despacho de almacén sin envío asociado no tiene envío al que ir, así que la
 * fila no es un enlace (la integración que lo mandó se revisa en Integraciones).
 */
export function advisoryLink(advisory: Pick<ControlTowerAdvisoryView, "type" | "tripId" | "sourceId">): string | null {
  if (advisory.type === "SETTLEMENT_DISCREPANCY_OPEN" && advisory.sourceId) {
    return `/settlement?discrepancy=${advisory.sourceId}`;
  }
  return advisory.tripId ? `/trips/${advisory.tripId}` : null;
}

/** Una clave estable aunque falte `sourceId` (AWAITING_WAREHOUSE_DISPATCH) o `tripId`. */
export function advisoryKey(advisory: ControlTowerAdvisoryView, index: number): string {
  return `${advisory.type}-${advisory.sourceId ?? advisory.tripId ?? advisory.shipmentNumber ?? index}`;
}
