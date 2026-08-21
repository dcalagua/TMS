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
  /**
   * Google Maps JavaScript API key, or `null` when unset. `null` is a supported, non-error
   * state: the location map picker degrades to manual latitude/longitude entry instead of
   * failing to load. See `docs/integrations/GOOGLE_MAPS.md`.
   */
  readonly googleMapsApiKey: string | null
}

const DEFAULT_API_BASE_URL = 'http://localhost:8080/api/v1'
// Local Supabase CLI defaults. Not a real project: pointing at them without a running
// local stack fails sign-in with a normal network error instead of a blank screen, which
// is preferable to crashing the whole app when `.env.local` has not been created yet.
const DEFAULT_SUPABASE_URL = 'http://localhost:54321'
const DEFAULT_SUPABASE_ANON_KEY = 'local-development-anon-key-placeholder'

function readEnv(): AppEnv {
  const raw = import.meta.env

  return {
    apiBaseUrl: (raw.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL).replace(/\/+$/, ''),
    supabaseUrl: raw.VITE_SUPABASE_URL || DEFAULT_SUPABASE_URL,
    supabaseAnonKey: raw.VITE_SUPABASE_ANON_KEY || DEFAULT_SUPABASE_ANON_KEY,
    googleMapsApiKey: raw.VITE_GOOGLE_MAPS_API_KEY?.trim() || null,
  }
}

export const appEnv: AppEnv = readEnv()
