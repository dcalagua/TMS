import { Link } from 'react-router-dom'
import type { StatusTone } from './StatusBadge'

interface KpiCardProps {
  label: string
  /** Already formatted for the locale. Undefined while it is still loading. */
  value?: string
  /** One line saying what the number means or what to do about it. */
  hint?: string
  /** Bootstrap Icons class, e.g. `bi-inbox`. */
  icon: string
  /** Colours the icon tile and the value. Neutral by default - a wall of coloured
   * numbers tells the operator nothing about which one needs them. */
  tone?: StatusTone
  /** Makes the whole card a link to the list this number came from. */
  to?: string
  isLoading?: boolean
}

/**
 * One number on the dashboard.
 *
 * A KPI earns its place by answering "is there anything for me to do?", so the card is built
 * around the figure and the action, not around decoration: an icon tile to find it by, the
 * value at a size you can read across a desk, and one line saying what it means.
 *
 * When `to` is given the whole card navigates to the list it came from - a count the operator
 * cannot act on is a poster, not a control.
 */
export function KpiCard({ label, value, hint, icon, tone = 'neutral', to, isLoading = false }: KpiCardProps) {
  const body = (
    <>
      <span className={`tms-kpi-tile tms-kpi-tile-${tone}`} aria-hidden="true">
        <i className={`bi ${icon}`} />
      </span>
      <span className="tms-kpi-text">
        <span className="tms-kpi-label">{label}</span>
        {isLoading ? (
          <span className="tms-kpi-value tms-kpi-value-loading" aria-hidden="true" />
        ) : (
          <span className={`tms-kpi-value tms-kpi-value-${tone}`}>{value ?? '—'}</span>
        )}
        {hint && <span className="tms-kpi-hint">{hint}</span>}
      </span>
      {to && <i className="bi bi-arrow-right tms-kpi-go" aria-hidden="true" />}
    </>
  )

  if (to) {
    return (
      <Link to={to} className="tms-kpi tms-kpi-link">
        {body}
      </Link>
    )
  }

  return <div className="tms-kpi">{body}</div>
}
