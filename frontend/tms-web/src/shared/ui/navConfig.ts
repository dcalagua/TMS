import type { NavigationKey } from '../i18n/keys'

export interface NavLeaf {
  to: string
  /** Backend capability that gates visibility, for a leaf that stands outside any group. */
  capability?: string
  /** Key inside the `navigation` namespace. The menu never carries display text itself, so a
   * language switch cannot leave part of the navigation in the previous language. */
  labelKey: NavigationKey
  /** Bootstrap Icons class. Also what the collapsed rail shows in place of the label. */
  icon: string
}

export interface NavGroup {
  labelKey: NavigationKey
  /** 1-6, mapping to the categorical accents in `tokens.css`. Modules keep their colour
   * across screens so it becomes part of how the user finds them. */
  accent: 1 | 2 | 3 | 4 | 5 | 6
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
    accent: 1,
    capability: 'MASTER_DATA_VIEW',
    items: [
      { to: '/masters/origins', labelKey: 'items.origins', icon: 'bi-geo-alt' },
      { to: '/masters/destinations', labelKey: 'items.destinations', icon: 'bi-pin-map' },
      { to: '/masters/zones', labelKey: 'items.zones', icon: 'bi-bounding-box' },
      { to: '/masters/frequencies', labelKey: 'items.frequencies', icon: 'bi-calendar-week' },
      { to: '/masters/routes', labelKey: 'items.routes', icon: 'bi-signpost-split' },
    ],
  },
  {
    labelKey: 'groups.fleet',
    accent: 2,
    capability: 'FLEET_VIEW',
    items: [
      { to: '/fleet/carriers', labelKey: 'items.carriers', icon: 'bi-building' },
      { to: '/fleet/vehicle-types', labelKey: 'items.vehicleTypes', icon: 'bi-diagram-3' },
      { to: '/fleet/vehicles', labelKey: 'items.vehicles', icon: 'bi-truck-front' },
    ],
  },
  {
    labelKey: 'groups.orders',
    accent: 3,
    capability: 'ORDERS_VIEW',
    items: [{ to: '/orders', labelKey: 'items.orders', icon: 'bi-clipboard-check' }],
  },
  {
    labelKey: 'groups.planning',
    accent: 5,
    capability: 'PLANNING_VIEW',
    items: [{ to: '/planning', labelKey: 'items.planning', icon: 'bi-kanban' }],
  },
  {
    labelKey: 'groups.trips',
    accent: 6,
    capability: 'TRIPS_VIEW',
    items: [{ to: '/trips', labelKey: 'items.trips', icon: 'bi-map' }],
  },
]

/** The dashboard entry, kept apart because it belongs to no group. */
export const HOME_NAV: NavLeaf = { to: '/', labelKey: 'home', icon: 'bi-speedometer2' }

/**
 * Security, likewise outside the groups.
 *
 * It used to be a group of its own holding a single entry, which spent a section heading and a
 * rule on one link and left the column looking like it had a footer bolted on. It is simply
 * one more item now - the last one, because administration is not where the day's work is.
 */
export const ADMIN_NAV: NavLeaf = {
  to: '/admin/security',
  labelKey: 'items.security',
  icon: 'bi-shield-lock',
  capability: 'IAM_VIEW',
}
