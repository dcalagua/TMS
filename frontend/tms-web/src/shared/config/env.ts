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
   * soportado y no un error: los mapas de paradas degradan a un aviso en vez de fallar al
   * cargar. El selector de ubicación ya no la usa: va sobre Leaflet y no necesita clave.
   */
  readonly googleMapsApiKey: string | null;
  /** Plantilla de teselas del mapa. Por defecto las públicas de OpenStreetMap. */
  readonly mapTileUrl: string;
  /** Crédito que el mapa muestra sobre las teselas. Obligatorio, y cambia con el proveedor. */
  readonly mapTileAttribution: string;
  /** Endpoint de búsqueda de direcciones compatible con Nominatim. */
  readonly geocoderUrl: string;
}

const DEFAULT_API_BASE_URL = "http://localhost:8080/api/v1";
// Valores por defecto del CLI local de Supabase. No son un proyecto real: apuntar ahí sin el
// stack local levantado falla el login con un error de red normal, que es preferible a
// tumbar la app entera cuando todavía no se ha creado `.env.local`.
const DEFAULT_SUPABASE_URL = "http://localhost:54321";
const DEFAULT_SUPABASE_ANON_KEY = "local-development-anon-key-placeholder";
// Teselas y geocodificador públicos de OpenStreetMap. A diferencia de los valores de Supabase
// de arriba, estos sí funcionan tal cual: el mapa se ve sin configurar nada. Lo que no hacen es
// escalar - la política de uso de la fundación desaconseja apoyarse en ellos para tráfico de
// producción, y por eso los tres son sobreescribibles.
export const DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
const DEFAULT_TILE_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap</a>';
const DEFAULT_GEOCODER_URL = "https://nominatim.openstreetmap.org/search";

function readEnv(): AppEnv {
  const raw = import.meta.env;

  return {
    apiBaseUrl: (raw.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL).replace(/\/+$/, ""),
    supabaseUrl: raw.VITE_SUPABASE_URL || DEFAULT_SUPABASE_URL,
    supabaseAnonKey: raw.VITE_SUPABASE_ANON_KEY || DEFAULT_SUPABASE_ANON_KEY,
    googleMapsApiKey: raw.VITE_GOOGLE_MAPS_API_KEY?.trim() || null,
    mapTileUrl: raw.VITE_MAP_TILE_URL?.trim() || DEFAULT_TILE_URL,
    mapTileAttribution: raw.VITE_MAP_TILE_ATTRIBUTION?.trim() || DEFAULT_TILE_ATTRIBUTION,
    geocoderUrl: (raw.VITE_GEOCODER_URL?.trim() || DEFAULT_GEOCODER_URL).replace(/\/+$/, ""),
  };
}

export const appEnv: AppEnv = readEnv();
