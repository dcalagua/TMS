/**
 * Typed access to build-time configuration.
 *
 * Business data always travels React -> Spring Boot -> PostgreSQL. `apiBaseUrl` is
 * therefore the only endpoint the application calls for business data. The Supabase
 * values exist for authentication only (V1 rule) and are consumed from Step 04.
 */
export interface AppEnv {
  /** Absolute base URL of the TMS backend, for example `http://localhost:8080/api/v1`. */
  readonly apiBaseUrl: string
  /** Supabase project URL. Authentication only. */
  readonly supabaseUrl: string
  /** Supabase anon (publishable) key. Authentication only; never a service-role key. */
  readonly supabaseAnonKey: string
}

const DEFAULT_API_BASE_URL = 'http://localhost:8080/api/v1'

function readEnv(): AppEnv {
  const raw = import.meta.env

  return {
    apiBaseUrl: (raw.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL).replace(/\/+$/, ''),
    supabaseUrl: raw.VITE_SUPABASE_URL ?? '',
    supabaseAnonKey: raw.VITE_SUPABASE_ANON_KEY ?? '',
  }
}

export const appEnv: AppEnv = readEnv()
