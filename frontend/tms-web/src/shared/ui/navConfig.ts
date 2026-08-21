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
      // First, and above Origins/Destinations on purpose: since migration V14 a location is
      // the canonical record and those two are its compatibility projections, so this is where
      // a place should be created. They stay in the menu because routes, orders and planning
      // still speak their vocabulary (docs/architecture/ADR_LOCATION_MODEL.md).
      { to: '/masters/locations', labelKey: 'items.locations', icon: 'bi-geo-alt-fill' },
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
      // Last in the group, after the things they drive: a driver is only assignable once there
      // is a vehicle to put them in, so this is also the order a fleet gets set up in.
      { to: '/fleet/drivers', labelKey: 'items.drivers', icon: 'bi-person-badge' },
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
  // Last, and behind its own capability: tariffs are commercial information, and a role that
  // runs the day does not automatically get to see what the day is worth (migration V30).
  {
    labelKey: 'groups.rates',
    accent: 4,
    capability: 'RATES_VIEW',
    items: [{ to: '/rates/rate-cards', labelKey: 'items.rateCards', icon: 'bi-cash-coin' }],
  },
]

/** The dashboard entry, kept apart because it belongs to no group. */
export const HOME_NAV: NavLeaf = { to: '/', labelKey: 'home', icon: 'bi-speedometer2' }

/**
 * The control tower, directly under the dashboard and likewise outside the groups.
 *
 * It is not a module. Every module in the list above owns something - locations, vehicles, orders,
 * shipments - and this owns nothing: it is one way of looking at the day those modules produced,
 * which is exactly what the dashboard is, and the two belong together at the top rather than
 * filed under whichever module happens to supply most of the rows.
 *
 * Its own capability, not the trips one: `monitoring.transport:read` has meant "see where the
 * transport is" since migration V3, and a role can hold it - customer service, a duty manager -
 * without being entitled to open a shipment for editing. Hiding is UX only; the endpoint behind
 * it checks the same permission server-side.
 */
export const CONTROL_TOWER_NAV: NavLeaf = {
  to: '/control-tower',
  labelKey: 'items.controlTower',
  icon: 'bi-broadcast-pin',
  capability: 'TRANSPORT_MONITOR_VIEW',
}

/**
 * Reports & KPIs, beside the control tower and for the same reason.
 *
 * The two are one pair: the tower is today and the report is the quarter, both describe days the
 * modules produced rather than owning anything themselves, and filing either under whichever module
 * supplies most of its rows would put it where nobody would look for it.
 *
 * The same capability, too. `monitoring.transport:read` is what the report's endpoints check, so
 * anything else here would hide a screen from somebody entitled to it or offer one they cannot open
 * - the finer questions (may this account see cost, tariffs, the order backlog) are decided per
 * card, inside the response.
 */
export const REPORTS_NAV: NavLeaf = {
  to: '/reporting',
  labelKey: 'items.reports',
  icon: 'bi-bar-chart-line',
  capability: 'TRANSPORT_MONITOR_VIEW',
}

/**
 * The leaves that sit above the module groups, in order.
 *
 * Iterated by the sidebar, the search and the breadcrumb rather than each of them repeating a
 * capability check per leaf: three copies of that condition is how the third one ends up offering a
 * screen the menu is hiding.
 */
export const OVERVIEW_NAV: NavLeaf[] = [CONTROL_TOWER_NAV, REPORTS_NAV]

/**
 * Configuration, last and outside the module groups.
 *
 * It was a single "Seguridad" link pointing at a placeholder until job 12; it is a group again now
 * that there are two screens behind it and both are administration rather than operation - what
 * this company *is* (its name, its zone, its document prefixes) and who may act in it. Keeping it
 * apart from Maestros is the point: a master is data the operation uses every day, and these are
 * decisions taken once and then left alone.
 *
 * Its accent is shared with Tarifas rather than unique, because the palette has six categorical
 * accents and this is the seventh entry - and because this is not a module. The trailing slot and
 * the rule above it are what separate it, not a colour.
 *
 * Gated by {@code IAM_VIEW}, which is any of the four {@code iam.*:read} permissions. That is
 * deliberately loose: a PLANNER holds {@code iam.company:read} and will see the section with the
 * company screen read-only, while the people screen behind {@code iam.user:read} answers 403 to
 * them. Hiding is UX; each endpoint decides for itself.
 */
export const SETTINGS_NAV: NavGroup = {
  labelKey: 'groups.settings',
  accent: 4,
  capability: 'IAM_VIEW',
  items: [
    { to: '/settings/company', labelKey: 'items.companySettings', icon: 'bi-building-gear' },
    { to: '/settings/users', labelKey: 'items.usersAccess', icon: 'bi-people' },
    // Carries its own capability, unlike its two neighbours: the group is gated by IAM_VIEW, and
    // issuing a machine credential is deliberately not the same decision as inviting a person
    // (see Capability.INTEGRATION_VIEW in tms-api). A company administrator holds both and sees
    // all three; an installation that splits them gets a menu that matches.
    {
      to: '/settings/integrations',
      capability: 'INTEGRATION_VIEW',
      labelKey: 'items.integrations',
      icon: 'bi-plug',
    },
  ],
}

/**
 * Every group, module and configuration alike.
 *
 * The sidebar draws the two apart - modules in the column, configuration in the trailing slot -
 * but the breadcrumb and the menu search want one list: a screen that is reachable must be
 * searchable and must have a breadcrumb, and the previous single "Seguridad" leaf had neither
 * precisely because it lived outside the only list those two iterated.
 */
export const ALL_NAV_GROUPS: NavGroup[] = [...NAV_GROUPS, SETTINGS_NAV]
