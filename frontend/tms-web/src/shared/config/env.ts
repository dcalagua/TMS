/**
 * Acceso tipado a la configuración de build.
 *
 * Los datos de negocio viajan siempre React → Spring Boot → PostgreSQL. `apiBaseUrl` es por
 * tanto el único endpoint al que la aplicación llama para datos de negocio. Los valores de
 * Supabase existen solo para autenticación (regla V1).
 */
export interface AppEnv {
  /** URL base absoluta del backend de eTMS, por ejemplo `http://localhost:8080/api/v1`. */
  readonly apiBaseUrl: string;
  /** URL del proyecto Supabase. Solo autenticación. */
  readonly supabaseUrl: string;
  /** Anon (publishable) key de Supabase. Solo autenticación; nunca una service-role key. */
  readonly supabaseAnonKey: string;
  /**
   * Clave de la Google Maps JavaScript API, o `null` si no está puesta. `null` es un estado
   * soportado y no un error: el selector de ubicación degrada a latitud/longitud manual en
   * vez de fallar al cargar.
   */
  readonly googleMapsApiKey: string | null;
}

const DEFAULT_API_BASE_URL = "http://localhost:8080/api/v1";
// Valores por defecto del CLI local de Supabase. No son un proyecto real: apuntar ahí sin el
// stack local levantado falla el login con un error de red normal, que es preferible a
// tumbar la app entera cuando todavía no se ha creado `.env.local`.
const DEFAULT_SUPABASE_URL = "http://localhost:54321";
const DEFAULT_SUPABASE_ANON_KEY = "local-development-anon-key-placeholder";

function readEnv(): AppEnv {
  const raw = import.meta.env;

  return {
    apiBaseUrl: (raw.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL).replace(/\/+$/, ""),
    supabaseUrl: raw.VITE_SUPABASE_URL || DEFAULT_SUPABASE_URL,
    supabaseAnonKey: raw.VITE_SUPABASE_ANON_KEY || DEFAULT_SUPABASE_ANON_KEY,
    googleMapsApiKey: raw.VITE_GOOGLE_MAPS_API_KEY?.trim() || null,
  };
}

export const appEnv: AppEnv = readEnv();
