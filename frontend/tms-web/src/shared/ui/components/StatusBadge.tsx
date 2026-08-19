export type StatusTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info'

const TONE_CLASS: Record<StatusTone, string> = {
  neutral: 'text-bg-secondary',
  success: 'text-bg-success',
  warning: 'text-bg-warning',
  danger: 'text-bg-danger',
  info: 'text-bg-info',
}

/** A small colored label for record status (active/inactive, order status, ...). Purely
 * presentational - it never decides what a user may do. */
export function StatusBadge({ label, tone = 'neutral' }: { label: string; tone?: StatusTone }) {
  return <span className={`badge rounded-pill ${TONE_CLASS[tone]}`}>{label}</span>
}
