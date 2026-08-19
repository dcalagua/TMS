import type { CapacityDimension } from '../../api/planningApi'

interface CapacityBarProps {
  label: string
  unit: string
  dimension: CapacityDimension
}

const WARNING_THRESHOLD = 85

function formatAmount(value: number, unit: string): string {
  const rounded = Math.round(value * 100) / 100
  return `${rounded} ${unit}`
}

/**
 * Renders one capacity dimension exactly as the backend computed it - it never derives a
 * percentage, a warning or an overcapacity verdict itself (`docs/domain/CAPACITY_MODEL.md`,
 * "The frontend is never trusted"). Three states the backend documents as genuinely different
 * are rendered differently, not approximated into one bar shape:
 *
 * - `unlimited` (no vehicle attached yet): a muted, unfilled bar with no percentage.
 * - a real zero limit (`limit === 0`, `percentUsed === null`): reporting 0% or 100% would both
 *   be a lie, so this renders "n/a" text plus an explicit over-capacity line if anything was
 *   still assigned against it.
 * - a normal limit: a filled bar, colored success/warning/danger by the backend's own
 *   `exceeded` flag and a client-side "near capacity" warning threshold that only ever changes
 *   color, never the verdict.
 */
export function CapacityBar({ label, unit, dimension }: CapacityBarProps) {
  const { used, limit, percentUsed, exceeded, unlimited } = dimension

  if (unlimited) {
    return (
      <div className="mb-2">
        <div className="d-flex justify-content-between small mb-1">
          <span>{label}</span>
          <span className="text-body-secondary">{formatAmount(used, unit)} · unlimited</span>
        </div>
        <div className="progress" role="progressbar" aria-label={`${label} capacity: unlimited`} style={{ height: '0.5rem' }}>
          <div className="progress-bar bg-secondary bg-opacity-25 w-100" />
        </div>
      </div>
    )
  }

  if (percentUsed === null) {
    return (
      <div className="mb-2">
        <div className="d-flex justify-content-between small mb-1">
          <span>{label}</span>
          <span className={exceeded ? 'text-danger fw-semibold' : 'text-body-secondary'}>
            {formatAmount(used, unit)} / {formatAmount(limit ?? 0, unit)} · n/a
          </span>
        </div>
        <div
          className="progress"
          role="progressbar"
          aria-label={`${label} capacity: no headroom to measure`}
          style={{ height: '0.5rem' }}
        >
          <div className={`progress-bar ${exceeded ? 'bg-danger' : 'bg-secondary bg-opacity-25'} w-100`} />
        </div>
        {exceeded && <div className="small text-danger">Over capacity: this dimension has no room at all.</div>}
      </div>
    )
  }

  const width = Math.min(100, Math.max(0, percentUsed))
  const barTone = exceeded ? 'bg-danger' : percentUsed >= WARNING_THRESHOLD ? 'bg-warning' : 'bg-success'

  return (
    <div className="mb-2">
      <div className="d-flex justify-content-between small mb-1">
        <span>{label}</span>
        <span className={exceeded ? 'text-danger fw-semibold' : 'text-body-secondary'}>
          {formatAmount(used, unit)} / {formatAmount(limit ?? 0, unit)} ({percentUsed}%)
        </span>
      </div>
      <div
        className="progress"
        role="progressbar"
        aria-label={`${label} capacity: ${percentUsed}% used`}
        aria-valuenow={Math.round(percentUsed)}
        aria-valuemin={0}
        aria-valuemax={100}
        style={{ height: '0.5rem' }}
      >
        <div className={`progress-bar ${barTone}`} style={{ width: `${width}%` }} />
      </div>
      {exceeded && <div className="small text-danger">Over capacity.</div>}
    </div>
  )
}
