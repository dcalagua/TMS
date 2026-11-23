import { appEnv } from "../config/env";
import { ApiError, type ProblemDetails } from "./httpClient";

/**
 * «¿Olvidaste tu contraseña?» (ADR-031): el backend busca a la persona, pide a Supabase el enlace de
 * recuperación y lo envía por MailHub. Anónimo — quien lo usa no puede iniciar sesión —, así que va
 * con `fetch` y sin `Authorization`, como el seguimiento público.
 *
 * El backend responde 202 exista o no la cuenta; solo cambia con 400 (correo mal escrito), 429
 * (demasiados intentos) o 503 (el envío de correos no está configurado).
 */
export async function requestPasswordReset(email: string): Promise<void> {
  const response = await fetch(`${appEnv.apiBaseUrl}/auth/password-reset`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json, application/problem+json" },
    body: JSON.stringify({ email: email.trim() }),
    credentials: "omit",
    referrerPolicy: "no-referrer",
    cache: "no-store",
  });
  if (response.ok) return;
  const contentType = response.headers.get("content-type") ?? "";
  const payload: unknown = contentType.includes("json") ? await response.json().catch(() => null) : null;
  throw new ApiError(response.status, (payload as ProblemDetails | null) ?? null, "", `HTTP ${response.status}`);
}
