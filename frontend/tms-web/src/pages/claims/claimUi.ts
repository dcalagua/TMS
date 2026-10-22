import type { StatusTone } from "../../theme";
import type { ClaimDraft, ClaimStatus } from "../../shared/api/claimsApi";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDecimal, fmtQuantity } from "../../lib/locale";

/** El color de cada estado: abierto y en revisión piden trabajo; aceptado espera la recuperación. */
export const CLAIM_STATUS_TONE: Record<ClaimStatus, StatusTone> = {
  OPEN: "open",
  UNDER_REVIEW: "inProgress",
  ACCEPTED: "done",
  PARTIALLY_ACCEPTED: "done",
  REJECTED: "overdue",
  CLOSED: "neutral",
  CANCELLED: "cancelled",
};

/** La antigüedad dicha como se lee en una fila: «hoy», «1 día», «12 días». */
export function ageLabel(days: number): string {
  if (days <= 0) return t("hoy");
  return days === 1 ? t("1 día") : t("{{n}} días", { n: fmtQuantity(days) });
}

/** El tono de la antigüedad: más de un mes abierto ya es una deuda, no un trámite. */
export function ageTone(days: number, open: boolean): "error.main" | "warning.main" | "text.secondary" {
  if (!open) return "text.secondary";
  if (days > 30) return "error.main";
  if (days > 7) return "warning.main";
  return "text.secondary";
}

/**
 * La descripción con la que nace un reclamo abierto desde lo registrado del viaje. Es un borrador:
 * la persona lo corrige antes de guardar. Se arma en la pantalla para que salga en su idioma.
 */
export function draftDescription(draft: ClaimDraft): string {
  const parts: string[] = [];
  if (draft.deliveryResult) {
    parts.push(t("{{result}}: pedido {{order}} del envío {{shipment}}.", {
      result: enumLabel("deliveryResult", draft.deliveryResult),
      order: draft.orderNumber ?? "—",
      shipment: draft.shipmentNumber,
    }));
    for (const line of draft.refusedLines) {
      parts.push(t("Línea {{line}} ({{material}}): {{qty}} {{uom}} rechazadas.", {
        line: line.orderLineNumber ?? "—",
        material: line.materialCode ?? "—",
        qty: fmtDecimal(line.quantity, 3),
        uom: line.uom,
      }));
    }
    if (draft.refusedLines.length === 0 && draft.refusedPallets !== null && draft.refusedPallets > 0) {
      parts.push(t("{{n}} pallets rechazados.", { n: fmtDecimal(draft.refusedPallets, 2) }));
    }
    if (draft.deliveryNotes) parts.push(draft.deliveryNotes);
  } else if (draft.exceptionType) {
    parts.push(t("Incidencia «{{type}}» en el envío {{shipment}}.", {
      type: enumLabel("tripExceptionType", draft.exceptionType),
      shipment: draft.shipmentNumber,
    }));
    if (draft.exceptionNotes) parts.push(draft.exceptionNotes);
  }
  return parts.join(" ");
}
