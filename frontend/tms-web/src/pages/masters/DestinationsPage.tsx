import { LocationsPage } from './LocationsPage'

/**
 * Destinos: the Locations master, filtered to the places this company may deliver to. The
 * counterpart of `OriginsPage` - see that file for why both are one component.
 *
 * A store appears here and in Orígenes when it both receives deliveries and ships its returns.
 * That is one row shown by two questions, not two records.
 */
export function DestinationsPage() {
  return <LocationsPage view="DESTINATION" />
}
