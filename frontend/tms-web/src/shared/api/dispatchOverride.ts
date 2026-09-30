import { t } from "../../lib/i18n";
import type { DispatchConfirmationMode } from "./administrationApi";
import { ApiError } from "./httpClient";
import { describePlanningError } from "./problemMessages";

/**
 * El despacho manual frente a la confirmación del almacén (ADR-013 §4 y §13.8).
 *
 * En una empresa `EXTERNAL_REQUIRED` un despacho sin motivo responde 409 con este código; con
 * motivo pero sin `planning.trip:dispatch-override` responde 403. Todo lo que decide la pantalla
 * sale de aquí para que el botón, el diálogo y el mensaje de error no discrepen.
 */
export const DISPATCH_REQUIRES_EXTERNAL_CONFIRMATION = "dispatch-requires-external-confirmation";

/** El permiso que el backend comprueba para despachar por encima del almacén. */
export const DISPATCH_OVERRIDE_PERMISSION = "planning.trip:dispatch-override";

/** Longitud máxima del motivo, la misma que valida `TripExecutionRequest.overrideReason`. */
export const OVERRIDE_REASON_MAX_LENGTH = 500;

export function isExternalConfirmationRequired(error: unknown): boolean {
  return error instanceof ApiError && error.code === DISPATCH_REQUIRES_EXTERNAL_CONFIRMATION;
}

/**
 * Qué hace el botón de despacho.
 *
 * - `DIRECT`: despacho normal (MANUAL, HYBRID, o modo desconocido: el servidor decide y, si
 *   exige confirmación externa, lo dice con el 409 que la pantalla sabe tratar).
 * - `OVERRIDE`: EXTERNAL_REQUIRED y quien mira tiene el permiso: se pide un motivo.
 * - `WAIT_FOR_WAREHOUSE`: EXTERNAL_REQUIRED sin el permiso. No se ofrece la acción, porque la
 *   única respuesta posible sería un rechazo.
 */
export type DispatchAction = "DIRECT" | "OVERRIDE" | "WAIT_FOR_WAREHOUSE";

export function dispatchActionFor(
  mode: DispatchConfirmationMode | null | undefined,
  canOverride: boolean,
): DispatchAction {
  if (mode !== "EXTERNAL_REQUIRED") return "DIRECT";
  return canOverride ? "OVERRIDE" : "WAIT_FOR_WAREHOUSE";
}

/** Valida el motivo antes de mandarlo: obligatorio y de 1 a 500 caracteres tras recortar. */
export function normalizeOverrideReason(reason: string | null | undefined): string | null {
  const trimmed = (reason ?? "").trim();
  if (trimmed === "" || trimmed.length > OVERRIDE_REASON_MAX_LENGTH) return null;
  return trimmed;
}

/**
 * El mensaje de un despacho rechazado. Un 403 durante un override no es "no tienes permiso"
 * a secas: dice cuál falta, para que el despachador sepa a quién pedírselo.
 */
export function describeDispatchError(error: ApiError, overriding: boolean): string {
  if (overriding && error.status === 403 && error.code === "access-denied") {
    return t("No tienes permiso para despachar sin la confirmación del almacén (planning.trip:dispatch-override). Pide a un administrador que lo despache o te asigne el permiso.");
  }
  // El 409 de confirmación externa tiene su copy en `problemMessages`, como cualquier código.
  return describePlanningError(error);
}
