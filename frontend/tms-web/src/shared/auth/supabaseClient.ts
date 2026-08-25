import { createClient } from "@supabase/supabase-js";
import { appEnv } from "../config/env";

/**
 * El único uso directo de Supabase en la app (regla V1): la autenticación.
 *
 * `persistSession`/`autoRefreshToken` dejan que supabase-js mantenga la sesión válida en
 * segundo plano; `AuthContext` lee la sesión que este cliente tenga en cada momento en lugar
 * de gestionar tokens por su cuenta. Ninguna tabla de negocio se consulta nunca por aquí.
 */
export const supabase = createClient(appEnv.supabaseUrl, appEnv.supabaseAnonKey, {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: false,
  },
});
