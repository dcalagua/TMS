import { t } from "../../lib/i18n";
import { isAuthFailureResponse, type ApiError, type ProblemCode } from "./httpClient";

/**
 * Texto de cara al usuario para cada `code` del backend (API_CONVENTIONS §4.1).
 *
 * La rama es siempre por `code`, nunca por `detail`: `detail` es prosa pensada para los logs,
 * está escrita en un solo idioma y puede reescribirse sin avisar. Cada código tiene aquí su
 * copy en español, y la traducción al inglés la resuelve el diccionario de `lib/i18n.ts`.
 */
const CODE_COPY: Record<ProblemCode, string> = {
  "unauthenticated": "Tu sesión expiró. Vuelve a iniciar sesión.",
  "invalid-token": "Tu sesión ya no es válida. Vuelve a iniciar sesión.",
  "principal-not-provisioned":
    "Tu cuenta aún no está dada de alta en TMS. Contacta a un administrador para solicitar acceso.",
  "company-scope-required": "No hay una compañía seleccionada. Elige una compañía e inténtalo de nuevo.",
  "company-scope-invalid": "La compañía seleccionada no es válida. Elige una compañía e inténtalo de nuevo.",
  "company-scope-forbidden": "Ya no tienes acceso a esa compañía. Elige otra.",
  "access-denied": "No tienes permiso para realizar esta acción.",
  "validation-failed": "Hay campos que debes corregir.",
  "malformed-request": "No se pudo procesar la solicitud.",
  "resource-not-found": "No se encontró el elemento solicitado.",
  "conflict": "Este cambio entra en conflicto con otra actualización. Recarga e inténtalo de nuevo.",
  "feature-not-configured":
    "Esta funcionalidad no está configurada en esta instalación. Un administrador debe habilitarla antes de poder usarla.",
  "internal-error": "Ocurrió un error de nuestro lado. Vuelve a intentarlo.",
};

const FALLBACK = "Algo salió mal. Vuelve a intentarlo.";

function knownCode(code: string | null): code is ProblemCode {
  return code !== null && code in CODE_COPY;
}

/** Texto amable dirigido por `code` para un {@link ApiError} — nunca `error.message`/`detail`. */
export function describeApiError(error: ApiError): string {
  return t(knownCode(error.code) ? CODE_COPY[error.code] : FALLBACK);
}

/** True para los dos códigos que significan "el bearer token ya no sirve". Delega en
 * `httpClient` para que la UI y la lógica de reintento no puedan discrepar sobre qué es un
 * fallo de autenticación. */
export function isAuthProblem(error: ApiError): boolean {
  return isAuthFailureResponse(error.status, error.code);
}

/** True para el único código que significa "recarga `/me`, la empresa elegida está obsoleta". */
export function isCompanyScopeStale(error: ApiError): boolean {
  return error.code === "company-scope-forbidden";
}

/**
 * Prefiere el `detail` del backend sobre el copy genérico por código, para la única familia de
 * pantallas donde eso es el contrato documentado: los rechazos `conflict`/`malformed-request`
 * de planificación se escriben en el servidor para mostrarse tal cual a un planificador — los
 * fallos de capacidad nombran cada dimensión que no cupo, los de elegibilidad nombran el
 * desajuste de origen/fecha. Cualquier otra pantalla sigue usando `describeApiError`, de modo
 * que un cambio de redacción en `detail` no puede alterar su copy en silencio.
 */
export function describePlanningError(error: ApiError): string {
  if ((error.code === "conflict" || error.code === "malformed-request") && error.problem?.detail) {
    return error.problem.detail;
  }
  return describeApiError(error);
}

/**
 * La misma excepción que {@link describePlanningError}, para la importación masiva de pedidos.
 *
 * Los rechazos de fichero completo — "el fichero pesa más de 2 MB", "solo se pueden importar
 * .xlsx y .csv", "hace falta un sistema de origen" — se escriben en `OrderImportService` para
 * que los lea el operador que tiene el fichero delante, y cada uno nombra lo que tiene que
 * cambiar. Colapsar los tres en el copy genérico de `malformed-request` solo le diría que algo
 * iba mal en una subida en la que él no ve nada mal.
 *
 * Los problemas por fila nunca pasan por aquí: son datos, no un status de error, y viajan en
 * `OrderImportReport.issues` (por eso un fichero inservible sigue siendo un 200).
 */
export function describeImportError(error: ApiError): string {
  if (error.code === "malformed-request" && error.problem?.detail) {
    return error.problem.detail;
  }
  return describeApiError(error);
}
