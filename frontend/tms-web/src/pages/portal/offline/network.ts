import { ApiError, isAuthFailureResponse } from "../../../shared/api/httpClient";
import { t } from "../../../lib/i18n";

/** `navigator.onLine`, con `true` donde no existe. Solo dice "seguro que no hay red" cuando es `false`. */
export function isOnline(): boolean {
  return typeof navigator === "undefined" || navigator.onLine !== false;
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

/**
 * Un fallo sin respuesta: `fetch` lanzó (TypeError «Failed to fetch»), la red se cortó. Una
 * respuesta del servidor, por mala que sea, llega como `ApiError` y no cuenta.
 */
export function isNetworkError(error: unknown): boolean {
  return !(error instanceof ApiError) && !isAbort(error);
}

/** Sin red y sin ninguna copia guardada de esa pantalla en este teléfono. */
export class OfflineNoDataError extends Error {
  constructor() {
    super(t("Sin conexión y sin datos guardados en este teléfono. Abre el portal con señal al menos una vez."));
    this.name = "OfflineNoDataError";
  }
}

/**
 * Qué hacer con un reporte que no salió (ADR-019 §1):
 * - `network`: sin respuesta. Se queda en cola con espera exponencial.
 * - `server`: 5xx (o 408/429). Igual: el servidor no dijo que el reporte esté mal.
 * - `auth`: la sesión no vale. Se pausa la cola hasta que haya una sesión.
 * - `rejected`: 400/403/404/409. Queda FAILED con la razón del servidor, para que la persona decida.
 */
export type SendFailure = "network" | "server" | "auth" | "rejected";

export function classifySendError(error: unknown): SendFailure {
  if (!(error instanceof ApiError)) return "network";
  if (isAuthFailureResponse(error.status, error.code)) return "auth";
  if (error.status >= 500 || error.status === 408 || error.status === 429) return "server";
  return "rejected";
}

/** La razón que se guarda en la entrada: la del servidor si la dio. */
export function sendErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.problem?.detail ?? t("El servidor respondió {{status}}.", { status: error.status });
  }
  return t("Sin conexión con el servidor.");
}

/** Vibración corta donde el teléfono la permite. Nada en un ordenador ni en las pruebas. */
export function haptic(pattern: number | number[] = 20): void {
  try {
    if (typeof navigator !== "undefined" && typeof navigator.vibrate === "function") navigator.vibrate(pattern);
  } catch {
    /* algunos navegadores lanzan sin gesto del usuario */
  }
}
