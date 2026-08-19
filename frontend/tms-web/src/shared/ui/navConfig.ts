import type { NavigationKey } from '../i18n/keys'

export interface NavLeaf {
  to: string
  /** Key inside the `navigation` namespace. The menu never carries display text itself, so a
   * language switch cannot leave part of the navigation in the previous language. */
  labelKey: NavigationKey
}

export interface NavGroup {
  labelKey: NavigationKey
  /** Backend capability name (`shared/security/Capability` in tms-api) that gates visibility.
   * `undefined` means always visible. Hiding is UX only - the backend re-checks every call. */
  capability?: string
  items: NavLeaf[]
}

/** Initial navigation, matching the step brief's groups. Screens that are not built yet still
 * get a route and a clean placeholder rather than a disabled link - the module exists, it is
 * just not implemented. */
export const NAV_GROUPS: NavGroup[] = [
  {
    labelKey: 'groups.masters',
    capability: 'MASTER_DATA_VIEW',
    items: [
      { to: '/masters/origins', labelKey: 'items.origins' },
      { to: '/masters/destinations', labelKey: 'items.destinations' },
      { to: '/masters/zones', labelKey: 'items.zones' },
      { to: '/masters/frequencies', labelKey: 'items.frequencies' },
      { to: '/masters/routes', labelKey: 'items.routes' },
    ],
  },
  {
    labelKey: 'groups.fleet',
    capability: 'FLEET_VIEW',
    items: [
      { to: '/fleet/carriers', labelKey: 'items.carriers' },
      { to: '/fleet/vehicle-types', labelKey: 'items.vehicleTypes' },
      { to: '/fleet/vehicles', labelKey: 'items.vehicles' },
    ],
  },
  {
    labelKey: 'groups.orders',
    capability: 'ORDERS_VIEW',
    items: [{ to: '/orders', labelKey: 'items.orders' }],
  },
  {
    labelKey: 'groups.planning',
    capability: 'PLANNING_VIEW',
    items: [{ to: '/planning', labelKey: 'items.planning' }],
  },
  {
    labelKey: 'groups.trips',
    capability: 'TRIPS_VIEW',
    items: [{ to: '/trips', labelKey: 'items.trips' }],
  },
  {
    labelKey: 'groups.administration',
    capability: 'IAM_VIEW',
    items: [{ to: '/admin/security', labelKey: 'items.security' }],
  },
]
