export interface NavLeaf {
  to: string
  label: string
}

export interface NavGroup {
  label: string
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
    label: 'Master Data',
    capability: 'MASTER_DATA_VIEW',
    items: [
      { to: '/masters/origins', label: 'Origins' },
      { to: '/masters/destinations', label: 'Destinations' },
      { to: '/masters/zones', label: 'Zones' },
      { to: '/masters/frequencies', label: 'Frequencies' },
      { to: '/masters/routes', label: 'Routes' },
    ],
  },
  {
    label: 'Fleet',
    capability: 'FLEET_VIEW',
    items: [
      { to: '/fleet/carriers', label: 'Carriers' },
      { to: '/fleet/vehicle-types', label: 'Vehicle types' },
      { to: '/fleet/vehicles', label: 'Vehicles' },
    ],
  },
  {
    label: 'Orders',
    capability: 'ORDERS_VIEW',
    items: [{ to: '/orders', label: 'Orders' }],
  },
  {
    label: 'Planning',
    capability: 'PLANNING_VIEW',
    items: [{ to: '/planning', label: 'Planning' }],
  },
  {
    label: 'Trips',
    capability: 'TRIPS_VIEW',
    items: [{ to: '/trips', label: 'Trips' }],
  },
  {
    label: 'Administration',
    capability: 'IAM_VIEW',
    items: [{ to: '/admin/security', label: 'Security' }],
  },
]
