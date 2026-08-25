import { LocationsPage } from "./LocationsPage";

/**
 * Destinos: el reverso de Orígenes, con el uso operacional clavado en DESTINATION.
 *
 * La misma tienda aparece aquí y en Orígenes cuando recibe entregas y despacha sus propias
 * devoluciones: una fila, dos lentes, que es justo el punto del modelo de ubicación.
 */
export function DestinationsPage() {
  return <LocationsPage view="DESTINATION" />;
}
