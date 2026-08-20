import { LocationsPage } from './LocationsPage'

/**
 * Orígenes: the Locations master, filtered to the places this company may ship from.
 *
 * There is no origins screen of its own any more, and there was never a reason for one. An
 * origin is not a kind of record - it is a physical place with the `ORIGIN` operational use
 * ticked, and the same place may be a destination too. Keeping the menu entry costs nothing and
 * a planner looking for "where can we dispatch from" expects to find it; keeping a second CRUD
 * behind it is what used to make the CD in Lima exist twice, with two addresses to maintain and
 * nothing linking them.
 *
 * `Nuevo origen` opens the same drawer as Ubicaciones, with the origin use pre-ticked, so a
 * place created here appears in the list it was created from.
 */
export function OriginsPage() {
  return <LocationsPage view="ORIGIN" />
}
