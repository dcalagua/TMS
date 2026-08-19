import { useTranslation } from 'react-i18next'
import type { CapacityDimension } from '../../api/planningApi'
import { useFormat } from '../../i18n/format'

export type CapacityUnit = 'weight' | 'volume' | 'pallets'

interface CapacityBarProps {
  /** Which dimension this is; selects both the label and the unit formatting. */
  kind: CapacityUnit
  dimension: CapacityDimension
}

/** A dimension this close to full is worth flagging before it is actually exceeded. This only
 * ever changes how the bar is presented - never the verdict, which is the backend's. */
const WARNING_THRESHOLD = 85

/**
 * One capacity dimension, rendered exactly as the backend computed it: the component never
 * derives a percentage, a warning or an over-capacity verdict itself
 * (`docs/domain/CAPACITY_MODEL.md`, "The frontend is never trusted").
 *
 * Each row shows the label, `used / limit` in the dimension's own unit, and the percentage,
 * so a planner can read the absolute numbers and the fill at a glance rather than inferring
 * load from a bar's length. State is never conveyed by colour alone: over-capacity and
 * near-limit both carry an icon and a written label, and the bar exposes its value to
 * assistive technology.
 *
 * Three states the backend documents as genuinely different are rendered differently rather
 * than approximated into one bar shape:
 *
 * - `unlimited` (no vehicle attached yet): a muted, unfilled bar with no percentage;
 * - a real zero limit (`limit === 0`, `percentUsed === null`): reporting 0% or 100% would both
 *   be a lie, so this says so in words plus an explicit over-capacity line if anything was
 *   assigned against it;
 * - a normal limit: a filled bar coloured by the backend's own `exceeded` flag and the
 *   near-capacity threshold above.
 */
export function CapacityBar({ kind, dimension }: CapacityBarProps) {
  const { t } = useTranslation('planning')
  const format = useFormat()
  const { used, limit, percentUsed, exceeded, unlimited } = dimension

  const label = t(`capacity.${kind}`)

  const amount = (value: number): string => {
    if (kind === 'weight') return format.weight(value)
    if (kind === 'volume') return format.volume(value)
    return `${format.decimal(value)} ${t('capacity.palletsUnit')}`
  }

  if (unlimited) {
    return (
      <div className="mb-2">
        <div className="d-flex justify-content-between align-items-baseline gap-2 small mb-1">
          <span className="text-body-secondary">{label}</span>
          <span className="text-body-secondary">
            {amount(used)} · {t('capacity.unlimited')}
          </span>
        </div>
        <div
          className="tms-capacity"
          role="progressbar"
          aria-label={t('capacity.ariaUnlimited', { label, used: amount(used) })}
        >
          <div className="tms-capacity-fill tms-capacity-fill-muted" style={{ width: '100%' }} />
        </div>
      </div>
    )
  }

  if (percentUsed === null) {
    return (
      <div className="mb-2">
        <div className="d-flex justify-content-between align-items-baseline gap-2 small mb-1">
          <span className="text-body-secondary">{label}</span>
          <span className={exceeded ? 'text-danger fw-semibold' : 'text-body-secondary'}>
            {amount(used)} / {amount(limit ?? 0)} · {t('capacity.noLimit')}
          </span>
        </div>
        <div className="tms-capacity" role="progressbar" aria-label={t('capacity.ariaNoLimit', { label })}>
          <div
            className={`tms-capacity-fill ${exceeded ? 'tms-capacity-fill-danger' : 'tms-capacity-fill-muted'}`}
            style={{ width: '100%' }}
          />
        </div>
        {exceeded && (
          <p className="small text-danger mb-0 d-flex align-items-center gap-1">
            <i className="bi bi-exclamation-triangle-fill" aria-hidden="true" />
            {t('capacity.noRoom')}
          </p>
        )}
      </div>
    )
  }

  const width = Math.min(100, Math.max(0, percentUsed))
  const nearLimit = !exceeded && percentUsed >= WARNING_THRESHOLD
  const fillTone = exceeded
    ? 'tms-capacity-fill-danger'
    : nearLimit
      ? 'tms-capacity-fill-warning'
      : 'tms-capacity-fill-success'

  return (
    <div className="mb-2">
      <div className="d-flex justify-content-between align-items-baseline gap-2 small mb-1">
        <span className="text-body-secondary">{label}</span>
        <span className={exceeded ? 'text-danger fw-semibold' : 'text-body'}>
          <span className="tms-code">
            {amount(used)} / {amount(limit ?? 0)}
          </span>{' '}
          <span className={exceeded ? '' : 'text-body-secondary'}>({format.percent(percentUsed, 1)})</span>
        </span>
      </div>
      <div
        className="tms-capacity"
        role="progressbar"
        aria-label={t('capacity.ariaUsage', {
          label,
          percent: format.percent(percentUsed, 1),
          limit: amount(limit ?? 0),
        })}
        aria-valuenow={Math.round(percentUsed)}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div className={`tms-capacity-fill ${fillTone}`} style={{ width: `${width}%` }} />
      </div>
      {(exceeded || nearLimit) && (
        <p className={`small mb-0 d-flex align-items-center gap-1 ${exceeded ? 'text-danger' : 'text-warning-emphasis'}`}>
          <i
            className={exceeded ? 'bi bi-exclamation-triangle-fill' : 'bi bi-exclamation-circle'}
            aria-hidden="true"
          />
          {exceeded ? t('capacity.exceeded') : t('capacity.nearLimit')}
        </p>
      )}
    </div>
  )
}
