import type {
  DeliveryResult, StopExecutionStatus, TripExceptionStatus, TripStatus,
} from "../api/planningApi";
import type { InvoiceStatus, MatchStatus } from "../api/settlementApi";
import type { TenderStatus } from "../api/tendersApi";
import type { StatusTone } from "../../theme";

/**
 * Cómo se colorea cada estado del ciclo de vida, en un solo sitio para que la lista y el espacio
 * de trabajo nunca discrepen sobre qué aspecto tiene "en tránsito".
 *
 * `IN_TRANSIT` es ámbar y no verde: un viaje en la carretera no es un viaje terminado, y ese
 * ámbar es lo que hace visible de un vistazo el trabajo pendiente del día en un tablero de 300
 * filas. El color nunca es la única señal: `StatusChip` lleva siempre también la etiqueta.
 */
export const TRIP_STATUS_TONE: Record<TripStatus, StatusTone> = {
  DRAFT: "neutral",
  CONFIRMED: "open",
  READY_FOR_DISPATCH: "open",
  IN_TRANSIT: "inProgress",
  COMPLETED: "done",
  CANCELLED: "cancelled",
};

/**
 * Cómo se colorea cada desenlace de parada.
 *
 * `SKIPPED` es ámbar y `FAILED` rojo, deliberadamente distintos: una parada saltada por decisión
 * es un desenlace planificado, mientras que una que se intentó y se rechazó es aquello por lo que
 * alguien tiene que llamar al cliente. Fundir las dos en un color sería fundir los dos hechos.
 */
export const STOP_EXECUTION_TONE: Record<StopExecutionStatus, StatusTone> = {
  PENDING: "neutral",
  ARRIVED: "open",
  IN_SERVICE: "open",
  COMPLETED: "done",
  SKIPPED: "inProgress",
  FAILED: "overdue",
};

/** Los problemas abiertos son los que todavía necesitan a alguien; los resueltos son historia. */
export const TRIP_EXCEPTION_TONE: Record<TripExceptionStatus, StatusTone> = {
  OPEN: "overdue",
  RESOLVED: "done",
};

/**
 * Cómo se colorea cada desenlace de entrega.
 *
 * `NOT_ATTEMPTED` es neutro y no rojo: no le pasó nada a *la mercancía*, simplemente la parada no
 * se sirvió, y de eso ya está informando el chip de la propia parada. `PARTIAL` es ámbar porque
 * alguien tiene que perseguir el resto, y `REJECTED`/`FAILED` son rojos porque alguien tiene que
 * llamar al cliente.
 */
export const DELIVERY_RESULT_TONE: Record<DeliveryResult, StatusTone> = {
  DELIVERED: "done",
  PARTIAL: "inProgress",
  REJECTED: "overdue",
  FAILED: "overdue",
  NOT_ATTEMPTED: "neutral",
};

/**
 * Cómo se colorea cada intento de oferta a transportista.
 *
 * `SENT` es ámbar y no azul, a diferencia de `CONFIRMED`: una oferta esperando respuesta es el
 * trabajo pendiente del despachador, y es el estado que se convierte calladamente en un problema
 * si el camión tiene que salir antes de que alguien conteste. `EXPIRED` es ámbar por lo mismo y
 * no rojo —nadie hizo nada mal, se acabó un reloj— mientras que `REJECTED` es rojo, porque un
 * transportista diciendo que no es aquello sobre lo que hay que actuar hoy. `CANCELLED` es
 * neutro: la retiramos a propósito.
 */
export const TENDER_STATUS_TONE: Record<TenderStatus, StatusTone> = {
  DRAFT: "neutral",
  SENT: "inProgress",
  ACCEPTED: "done",
  REJECTED: "overdue",
  EXPIRED: "inProgress",
  CANCELLED: "neutral",
};

/**
 * V46: la auditoría de flete.
 *
 * `DISCREPANCY` es `inProgress` (ámbar) y no `overdue`: una diferencia es trabajo pendiente, no un
 * fracaso, y el ámbar es lo que hace visible de un vistazo la cola de auditoría del día.
 * `UNMATCHABLE` es `neutral` a propósito - TMS no tiene con qué comparar, y pintarlo como problema
 * mandaría a un auditor a discutir con un transportista que no hizo nada mal.
 */
export const INVOICE_STATUS_TONE: Record<InvoiceStatus, StatusTone> = {
  RECEIVED: "neutral",
  MATCHING: "inProgress",
  MATCHED: "open",
  DISCREPANCY: "inProgress",
  UNDER_REVIEW: "inProgress",
  APPROVED: "done",
  REJECTED: "cancelled",
  EXPORTED: "done",
};

export const MATCH_STATUS_TONE: Record<MatchStatus, StatusTone> = {
  MATCHED: "done",
  DISCREPANCY: "inProgress",
  UNMATCHABLE: "neutral",
};
