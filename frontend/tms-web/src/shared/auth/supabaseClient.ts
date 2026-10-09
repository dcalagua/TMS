import { createClient, type SupportedStorage } from "@supabase/supabase-js";
import { appEnv } from "../config/env";

/**
 * "Recordarme" decide dónde vive la sesión: en `localStorage` sobrevive al cierre del
 * navegador; en `sessionStorage` muere con la pestaña. La preferencia se guarda aparte, en
 * `localStorage`, porque tiene que leerse antes de que exista ninguna sesión.
 *
 * Por defecto se recuerda: es lo que hacía la app antes de que existiera la casilla.
 */
const REMEMBER_KEY = "etms.auth.remember";

function remembers(): boolean {
  try {
    return localStorage.getItem(REMEMBER_KEY) !== "0";
  } catch {
    return true;
  }
}

export function setRememberSession(remember: boolean): void {
  try {
    localStorage.setItem(REMEMBER_KEY, remember ? "1" : "0");
  } catch {
    // Sin almacenamiento disponible la sesión no persiste en ningún caso; no hay nada que elegir.
  }
}

export function getRememberSession(): boolean {
  return remembers();
}

const sessionStore: SupportedStorage = {
  getItem: (key) => (remembers() ? localStorage : sessionStorage).getItem(key),
  setItem: (key, value) => (remembers() ? localStorage : sessionStorage).setItem(key, value),
  // Se borra de los dos: un cambio de preferencia entre sesiones no debe dejar un token huérfano.
  removeItem: (key) => {
    localStorage.removeItem(key);
    sessionStorage.removeItem(key);
  },
};

/**
 * El único uso directo de Supabase en la app (regla V1): la autenticación.
 *
 * `persistSession`/`autoRefreshToken` dejan que supabase-js mantenga la sesión válida en
 * segundo plano; `AuthContext` lee la sesión que este cliente tenga en cada momento en lugar
 * de gestionar tokens por su cuenta. Ninguna tabla de negocio se consulta nunca por aquí.
 *
 * `detectSessionInUrl` está activo por el enlace de recuperación de contraseña: Supabase
 * devuelve al usuario a `/reset-password` con la sesión de recuperación en la URL.
 */
export const supabase = createClient(appEnv.supabaseUrl, appEnv.supabaseAnonKey, {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: true,
    storage: sessionStore,
  },
});
