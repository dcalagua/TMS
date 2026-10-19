import { appEnv } from "../config/env";

/**
 * Búsqueda de direcciones contra un endpoint compatible con Nominatim.
 *
 * POR QUÉ NO HAY BÚSQUEDA MIENTRAS SE ESCRIBE. La política de uso de Nominatim pide como mucho
 * una petición por segundo y prohíbe el autocompletado masivo. Un campo que consulta en cada
 * tecla la incumple a los tres caracteres, así que la búsqueda se dispara sólo cuando la persona
 * pulsa Intro o el botón. No es una limitación técnica que haya que resolver luego: es la forma
 * correcta de usar un servicio gratuito mantenido por una fundación.
 *
 * Un despliegue con volumen apunta `VITE_GEOCODER_URL` a su propia instancia o a un proveedor
 * contratado. La respuesta que se lee aquí es el subconjunto que cualquier Nominatim devuelve.
 */

export interface GeocodeResult {
  label: string;
  lat: number;
  lng: number;
}

/** Lo que devuelve Nominatim y este módulo mira. El resto de campos se ignora a propósito. */
interface NominatimRow {
  display_name?: unknown;
  lat?: unknown;
  lon?: unknown;
}

export class GeocoderUnavailableError extends Error {}

const RESULT_LIMIT = 5;

/**
 * Devuelve hasta cinco candidatos, o una lista vacía si no hubo ninguno.
 *
 * Distingue los dos fracasos que para quien busca no son iguales: «no existe esa dirección»
 * (lista vacía) y «no pude preguntar» ({@link GeocoderUnavailableError}). La primera se arregla
 * escribiendo otra cosa; la segunda, no.
 *
 * `viewBox` sesga los resultados hacia lo que se está viendo sin excluir nada fuera, que es lo
 * que hace útil buscar «Av. Primavera» estando sobre Lima.
 */
export async function geocodeAddress(
  query: string,
  options: { signal?: AbortSignal; viewBox?: [number, number, number, number] } = {},
): Promise<GeocodeResult[]> {
  const params = new URLSearchParams({
    q: query,
    format: "jsonv2",
    limit: String(RESULT_LIMIT),
    addressdetails: "0",
  });
  if (options.viewBox) {
    params.set("viewbox", options.viewBox.join(","));
    params.set("bounded", "0");
  }

  let response: Response;
  try {
    response = await fetch(`${appEnv.geocoderUrl}?${params.toString()}`, {
      signal: options.signal,
      headers: { Accept: "application/json" },
    });
  } catch (cause) {
    // Una búsqueda cancelada no es un fallo: el llamante la abortó y no quiere ver un error.
    if (cause instanceof DOMException && cause.name === "AbortError") throw cause;
    throw new GeocoderUnavailableError("No se pudo contactar el servicio de búsqueda", { cause });
  }

  if (!response.ok) {
    throw new GeocoderUnavailableError(`El servicio de búsqueda respondió ${response.status}`);
  }

  const payload: unknown = await response.json();
  if (!Array.isArray(payload)) return [];

  return payload.flatMap((row: NominatimRow) => {
    const lat = Number(row.lat);
    const lng = Number(row.lon);
    // Una fila sin coordenadas numéricas no es un resultado a medias, es una que no se puede
    // colocar en el mapa; se descarta en vez de producir un marcador en el golfo de Guinea.
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return [];
    const label = typeof row.display_name === "string" && row.display_name.trim() !== ""
      ? row.display_name
      : `${lat.toFixed(6)}, ${lng.toFixed(6)}`;
    return [{ label, lat, lng }];
  });
}
