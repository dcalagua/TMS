import { LocationsPage } from "./LocationsPage";

/**
 * Orígenes: la misma pantalla de Ubicaciones con el uso operacional clavado en ORIGIN.
 *
 * No hay `/masterdata/origins` ni tabla `tms.origin` detrás: un origen es una `tms.location`
 * que tiene el rol ORIGIN. Esta pantalla existe porque así es como sigue pensando el trabajo un
 * planificador, no porque haya un maestro distinto.
 */
export function OriginsPage() {
  return <LocationsPage view="ORIGIN" />;
}
