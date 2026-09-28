import type { Eligibility, SchedulingReason, SchedulingReasonCode } from "../../shared/api/schedulingApi";
import type { StatusTone } from "../../theme";

/**
 * El color dice lo que la ADR-014 dice: bloqueado es rojo, un aviso es ámbar, elegible es verde. Un
 * aviso informativo (`ROUTE_NOT_CONFIGURED`) sigue siendo ámbar en la columna de elegibilidad,
 * pero su motivo se pinta neutro: se muestra y no pide nada.
 *
 * Aparte de los componentes que lo pintan para que el refresco en caliente siga funcionando.
 */
export const ELIGIBILITY_TONE: Record<Eligibility, StatusTone> = {
  ELIGIBLE: "done",
  WARNING: "inProgress",
  BLOCKED: "overdue",
};

/** Qué significa cada motivo y qué hacer, en palabras del planificador. */
export const REASON_HELP: Record<SchedulingReasonCode, string> = {
  MISSING_ORIGIN: "El origen no es una ubicación activa con rol de origen. Corrige el maestro o el pedido.",
  MISSING_DESTINATION: "El destino no es una ubicación activa con rol de destino. Corrige el maestro o el pedido.",
  MISSING_CAPACITY: "El pedido no tiene peso, volumen ni pallets. Completa las líneas o los totales declarados.",
  ROUTE_NOT_FOUND: "El origen tiene rutas activas y ninguna pasa por este destino. Agrega el destino a una ruta.",
  ROUTE_AMBIGUOUS: "Varias rutas activas pasan por este destino y ninguna se elige sola. Deja una sola.",
  ACTIVE_BLOCKING_HOLD: "El pedido tiene una retención bloqueante. Levántala para poder liberarlo.",
  CUTOFF_MISSED: "Pasó la hora de corte para esa fecha de despacho. Se puede liberar indicando un motivo.",
  FREQUENCY_OVERRIDE: "El calendario del destino o de la ruta no atiende esa fecha. Se puede liberar con motivo; la planificación automática no lo tomará ese día.",
  ROUTE_NOT_CONFIGURED: "El origen no tiene rutas configuradas. Es informativo: se libera sin motivo.",
};

export function reasonTone(reason: SchedulingReason): StatusTone {
  if (reason.severity === "BLOCKED") return "overdue";
  return reason.requiresOverride ? "inProgress" : "neutral";
}
