export type StatusTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info'

const TONE_CLASS: Record<StatusTone, string> = {
  neutral: 'tms-badge-neutral',
  success: 'tms-badge-success',
  warning: 'tms-badge-warning',
  danger: 'tms-badge-danger',
  info: 'tms-badge-info',
}

/**
 * A small labelled marker for record status. Purely presentational - it never decides what a
 * user may do.
 *
 * The tone is a colour *and* a dot, but the label is always present: status must never be
 * conveyed by hue alone, which excludes colour-blind operators and anyone printing a screen.
 */
export function StatusBadge({ label, tone = 'neutral' }: { label: string; tone?: StatusTone }) {
  return <span className={`tms-badge ${TONE_CLASS[tone]}`}>{label}</span>
}
