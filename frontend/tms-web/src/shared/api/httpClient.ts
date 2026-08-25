import { appEnv } from "../config/env";

/** Nombres de cabecera del contrato de API. En un solo sitio para que un renombrado no se
 * desincronice entre el cliente, la capa de auth y los tests. */
export const COMPANY_ID_HEADER = "X-Company-Id";
export const CORRELATION_ID_HEADER = "X-Correlation-Id";

/** Los valores de `code` que documenta el backend (API_CONVENTIONS §4.1). */
export type ProblemCode =
  | "unauthenticated"
  | "invalid-token"
  | "principal-not-provisioned"
  | "company-scope-required"
  | "company-scope-invalid"
  | "company-scope-forbidden"
  | "access-denied"
  | "validation-failed"
  | "malformed-request"
  | "resource-not-found"
  | "conflict"
  /** Una capacidad opcional para la que este despliegue no está configurado — hoy, los webhooks salientes. */
  | "feature-not-configured"
  | "internal-error";

export interface ProblemFieldError {
  field: string;
  message: string;
}

/** Un documento `application/problem+json` (RFC 9457), tal y como lo forma `ApiExceptionHandler`. */
export interface ProblemDetails {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: ProblemCode | string;
  timestamp?: string;
  correlationId?: string;
  errors?: ProblemFieldError[];
}

function isProblemDetails(payload: unknown): payload is ProblemDetails {
  return typeof payload === "object" && payload !== null && "code" in payload;
}

/**
 * El error de cualquier respuesta no-2xx del backend. Lleva el status HTTP y el `code` estable
 * del documento Problem Details, para que quien lo consuma ramifique por `code` y nunca por
 * `detail` (API_CONVENTIONS §4): `detail` es prosa para humanos y puede reescribirse.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ProblemCode | string | null;
  readonly correlationId: string | null;
  readonly fieldErrors: ProblemFieldError[];
  readonly problem: ProblemDetails | null;

  constructor(status: number, problem: ProblemDetails | null, correlationId: string, fallbackMessage: string) {
    super(problem?.detail ?? fallbackMessage);
    this.name = "ApiError";
    this.status = status;
    this.code = problem?.code ?? null;
    this.correlationId = problem?.correlationId ?? correlationId;
    this.fieldErrors = problem?.errors ?? [];
    this.problem = problem;
  }
}

/** Los dos `code` que significan "este bearer token ya no se acepta". Un 401 pelado cuenta
 * también: es lo que responde el backend cuando no llegó ninguna cabecera `Authorization`. */
const AUTH_FAILURE_CODES: ReadonlySet<string> = new Set(["unauthenticated", "invalid-token"]);

/** Compartido por el reintento de aquí y por `problemMessages.isAuthProblem`, para que
 * "¿esto es un fallo de autenticación?" tenga exactamente una definición en la app. */
export function isAuthFailureResponse(status: number, code: string | null): boolean {
  return status === 401 || (code !== null && AUTH_FAILURE_CODES.has(code));
}

/**
 * Provee el bearer token de las peticiones salientes. `AuthContext` registra un lector
 * síncrono sobre la sesión que tiene en ese momento; hasta que exista una sesión las
 * peticiones salen sin autenticar y el backend responde 401, que es el comportamiento correcto.
 */
type TokenProvider = () => Promise<string | null> | string | null;

let tokenProvider: TokenProvider = () => null;

export function setAuthTokenProvider(provider: TokenProvider): void {
  tokenProvider = provider;
}

/**
 * Intenta obtener un access token fresco para una petición que el backend acaba de rechazar,
 * resolviendo a `null` cuando la sesión realmente no se puede recuperar. Lo registra
 * `AuthContext`; el de por defecto se niega, así que una app que nunca registre uno
 * sencillamente nunca reintenta.
 */
type AuthRefreshHandler = () => Promise<string | null>;

let authRefreshHandler: AuthRefreshHandler = () => Promise.resolve(null);

export function setAuthRefreshHandler(handler: AuthRefreshHandler): void {
  authRefreshHandler = handler;
}

let inFlightRefresh: Promise<string | null> | null = null;

/**
 * Refresh de un solo vuelo. Varias pantallas fallando con 401 en el mismo instante deben
 * producir un refresh, no uno cada una: si no, una página con seis queries dispara seis
 * refreshes y los últimos invalidan el token que acaban de obtener los primeros.
 */
function refreshAuthOnce(): Promise<string | null> {
  inFlightRefresh ??= Promise.resolve()
    .then(() => authRefreshHandler())
    .catch(() => null)
    .finally(() => {
      inFlightRefresh = null;
    });
  return inFlightRefresh;
}

/** Costura para tests: descarta cualquier refresh en vuelo para que un test no filtre estado al siguiente. */
export function resetAuthRefreshState(): void {
  inFlightRefresh = null;
}

/**
 * Reacción central a una respuesta fallida, registrada por las capas de auth/empresa en vez de
 * manejarse ad hoc en cada llamada. Los handlers no deben lanzar nuevas peticiones de forma
 * síncrona desde el callback: así es como un handler de 401 provoca un bucle infinito de refresh.
 *
 * Un fallo de autenticación llega aquí solo después de que el único refresh+reintento de abajo
 * haya fallado ya, así que un handler que cierre la sesión puede confiar en que la sesión es
 * de verdad irrecuperable y no simplemente vieja.
 */
type ResponseErrorHandler = (error: ApiError) => void;

const responseErrorHandlers = new Set<ResponseErrorHandler>();

export function onApiResponseError(handler: ResponseErrorHandler): () => void {
  responseErrorHandlers.add(handler);
  return () => responseErrorHandlers.delete(handler);
}

function reportResponseError(error: ApiError): void {
  for (const handler of responseErrorHandlers) {
    handler(error);
  }
}

function generateCorrelationId(): string {
  return crypto.randomUUID();
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  /** Un cuerpo `multipart/form-data` — subida de ficheros. Excluyente con `body`; el
   * `Content-Type` lo pone el navegador, porque solo él conoce el boundary del multipart. */
  formData?: FormData;
  signal?: AbortSignal;
  /** Parámetros de query; los valores `undefined` y `null` se omiten. */
  query?: Record<string, string | number | boolean | undefined | null>;
  /** UUID de empresa, enviado como `X-Company-Id` en los endpoints con ámbito de empresa. */
  companyId?: string;
  /** `blob` para una descarga; el camino de error sigue siendo JSON en cualquier caso, porque
   * una descarga fallida responde igualmente con un documento RFC 9457. */
  responseType?: "json" | "blob";
}

function buildUrl(path: string, query?: RequestOptions["query"]): string {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  const url = new URL(`${appEnv.apiBaseUrl}${normalizedPath}`);

  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined && value !== null) {
      url.searchParams.set(key, String(value));
    }
  }

  return url.toString();
}

async function parseBody(response: Response, responseType: "json" | "blob"): Promise<unknown> {
  if (response.status === 204 || response.headers.get("content-length") === "0") {
    return null;
  }

  const contentType = response.headers.get("content-type") ?? "";
  // Solo una petición de blob *con éxito* devuelve un blob. Una fallida trae problem+json, y
  // devolver eso como un Blob opaco perdería el error que hay que mostrar.
  if (responseType === "blob" && response.ok) {
    return { blob: await response.blob(), fileName: fileNameFromDisposition(response) };
  }
  return contentType.includes("json") ? response.json() : response.text();
}

/**
 * El nombre con el que el servidor pidió al navegador guardar el fichero. Se parsea en lugar
 * de adivinarse de la URL, para que renombrar la plantilla en el servidor renombre la
 * descarga; y solo se conserva el último segmento del path para que una cabecera manipulada
 * no pueda sugerir un directorio.
 */
function fileNameFromDisposition(response: Response): string | null {
  const disposition = response.headers.get("content-disposition");
  if (!disposition) return null;

  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  if (encoded !== undefined) return lastSegment(decodeURIComponent(encoded));

  const plain = /filename="?([^";]+)"?/i.exec(disposition)?.[1];
  return plain === undefined ? null : lastSegment(plain.trim());
}

/** El nombre de fichero pelado: lo que haya antes del último separador es un directorio que el
 * servidor no tiene por qué sugerir. Devuelve null para un valor que sea solo separadores. */
function lastSegment(value: string): string | null {
  const bare = value.split(/[\\/]/).pop();
  return bare === undefined || bare === "" ? null : bare;
}

/** A qué resuelve una petición con `responseType: 'blob'`. */
export interface DownloadedFile {
  blob: Blob;
  fileName: string | null;
}

interface Attempt {
  ok: boolean;
  payload: unknown;
  error: ApiError | null;
}

async function sendRequest(path: string, options: RequestOptions, token: string | null): Promise<Attempt> {
  const { method = "GET", body, formData, signal, query, companyId, responseType = "json" } = options;
  const correlationId = generateCorrelationId();

  const headers: Record<string, string> = {
    // Sigue siendo `application/json` para una descarga: lo que se negocia aquí es la forma
    // del *error*, y los endpoints que devuelven un fichero ignoran Accept en su caso de éxito.
    Accept: "application/json",
    [CORRELATION_ID_HEADER]: correlationId,
  };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (companyId) {
    headers[COMPANY_ID_HEADER] = companyId;
  }

  const response = await fetch(buildUrl(path, query), {
    method,
    headers,
    signal,
    // `Content-Type` se omite deliberadamente con FormData: fetch lo añade junto con el
    // boundary del multipart, y ponerlo a mano produce un cuerpo que el servidor no sabe partir.
    body: formData ?? (body === undefined ? undefined : JSON.stringify(body)),
  });

  const payload = await parseBody(response, responseType);

  if (response.ok) {
    return { ok: true, payload, error: null };
  }

  const problem = isProblemDetails(payload) ? payload : null;
  const error = new ApiError(
    response.status,
    problem,
    response.headers.get(CORRELATION_ID_HEADER) ?? correlationId,
    `${method} ${path} falló con ${response.status}`,
  );
  return { ok: false, payload, error };
}

/**
 * Ejecuta una petición JSON contra el backend de eTMS.
 *
 * Un fallo de autenticación tiene exactamente un intento de recuperación: refrescar la sesión
 * y reproducir la petición una vez si eso produjo un token distinto. No hay segundo reintento
 * ni reintento para ningún otro status, así que un backend que siga respondiendo 401 cuesta
 * una petición extra en total en lugar de entrar en bucle.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = (await tokenProvider()) ?? null;
  const attempt = await sendRequest(path, options, token);

  if (attempt.ok) {
    return attempt.payload as T;
  }

  const error = attempt.error as ApiError;

  if (isAuthFailureResponse(error.status, error.code) && options.signal?.aborted !== true) {
    const refreshedToken = await refreshAuthOnce();

    if (refreshedToken !== null && refreshedToken !== token) {
      const retry = await sendRequest(path, options, refreshedToken);
      if (retry.ok) {
        return retry.payload as T;
      }
      const retryError = retry.error as ApiError;
      reportResponseError(retryError);
      throw retryError;
    }
  }

  reportResponseError(error);
  throw error;
}

/**
 * Sube un cuerpo `multipart/form-data` y lee una respuesta JSON. Todo lo demás — el bearer
 * token, la cabecera de empresa, el único refresh-y-reintento, la traducción de Problem
 * Details — es de {@link apiRequest}, porque una subida que falló la autenticación tiene que
 * comportarse exactamente igual que cualquier otra petición que la falló.
 */
export function apiUpload<T>(
  path: string,
  options: { companyId?: string; formData: FormData; signal?: AbortSignal },
): Promise<T> {
  return apiRequest<T>(path, { method: "POST", ...options });
}

/** Descarga un fichero, resolviendo a sus bytes y al nombre que sugirió el servidor. */
export function apiDownload(
  path: string,
  options: { companyId?: string; query?: RequestOptions["query"]; signal?: AbortSignal } = {},
): Promise<DownloadedFile> {
  return apiRequest<DownloadedFile>(path, { ...options, responseType: "blob" });
}

/** Entrega al navegador un fichero ya descargado. Un `<a download>` con un object URL es la
 * única forma de que la descarga conserve el nombre que puso el servidor. */
export function saveDownloadedFile(file: DownloadedFile, fallbackName: string): void {
  const url = URL.createObjectURL(file.blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = file.fileName ?? fallbackName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
