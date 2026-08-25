import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import type { NavigationKey } from '../i18n/keys'
import { ALL_NAV_GROUPS, HOME_NAV, OVERVIEW_NAV } from './navConfig'

/**
 * Where the user is, derived from the navigation itself rather than from a per-page prop.
 *
 * The top bar used to repeat the product name, which the sidebar already carries three inches
 * to the left. This puts the one thing the bar is well placed to say - the path through the
 * menu - in that space instead, and it cannot drift out of sync with the menu because both read
 * the same config.
 */
export function Breadcrumb() {
  const { t } = useTranslation('navigation')
  const { pathname } = useLocation()

  const trail = resolveTrail(pathname)

  if (trail.length === 0) {
    return null
  }

  return (
    <nav aria-label={t('breadcrumb')} className="tms-min-w-0">
      <ol className="tms-breadcrumb">
        {trail.map((key, index) => (
          <li key={key} className="tms-breadcrumb-item" {...(index === trail.length - 1 ? { 'aria-current': 'page' as const } : {})}>
            {index > 0 && <i className="bi bi-chevron-right tms-breadcrumb-sep me-1" aria-hidden="true" />}
            {t(key)}
          </li>
        ))}
      </ol>
    </nav>
  )
}

/** Group label then item label, or just the home label. Longest match wins, so
 * `/masters/origins` is not claimed by a hypothetical `/masters`. */
function resolveTrail(pathname: string): NavigationKey[] {
  if (pathname === HOME_NAV.to) {
    return [HOME_NAV.labelKey]
  }
  // One level deep, like home: a leaf outside the groups has no group label to sit under, and
  // inventing one would put a heading in the breadcrumb that appears nowhere in the menu.
  const overview = OVERVIEW_NAV.find((item) => pathname === item.to)
  if (overview) {
    return [overview.labelKey]
  }

  let best: NavigationKey[] = []
  let bestLength = 0

  for (const group of ALL_NAV_GROUPS) {
    for (const item of group.items) {
      if ((pathname === item.to || pathname.startsWith(`${item.to}/`)) && item.to.length > bestLength) {
        best = [group.labelKey, item.labelKey]
        bestLength = item.to.length
      }
    }
  }

  return best
}
