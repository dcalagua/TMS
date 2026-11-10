import type { TransportEventView } from "../../shared/api/planningApi";
import { t } from "../../lib/i18n";
import { fmtKm } from "../../lib/locale";

/**
 * A qué distancia del destino se pulsó «Llegué» (V75): la historia del viaje y la tarjeta de la
 * parada lo dicen con las mismas palabras, el mismo redondeo y el mismo umbral de aviso.
 */

/**
 * Más lejos que esto, la distancia se pinta en aviso: un kilómetro es más de lo que se explica con
 * la precisión de un GPS de teléfono o con el tamaño de un local. No decide nada.
 */
export const FAR_FROM_STOP_KM = 1;

/** "350 m" por debajo de un kilómetro, redondeado a diez metros; "2,1 km" por encima. */
export function fmtDistance(km: number): string {
  return km < 1 ? `${Math.round((km * 1000) / 10) * 10} m` : fmtKm(km);
}

/** El aviso de qué mide la cifra, para un tooltip. */
export function arrivalDistanceHint(): string {
  return t("Distancia en línea recta entre la ubicación del teléfono al pulsar «Llegué» y las coordenadas del destino. Solo informa.");
}

/**
 * La distancia de la llegada de cada parada, por id de parada, sacada de la historia del viaje. Una
 * parada sin ubicación (sin permiso, sin señal) o cuyo destino no tiene coordenadas no aparece.
 */
export function arrivalDistanceByStop(events: TransportEventView[]): Map<string, number> {
  const byStop = new Map<string, number>();
  for (const event of events) {
    if (event.eventType === "ARRIVED_AT_STOP" && event.tripStopId && event.distanceToStopKm != null) {
      byStop.set(event.tripStopId, event.distanceToStopKm);
    }
  }
  return byStop;
}
