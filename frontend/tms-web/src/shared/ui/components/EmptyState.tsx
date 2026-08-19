import type { ReactNode } from 'react'

interface EmptyStateProps {
  title: string
  message?: string
  action?: ReactNode
  /** Bootstrap Icons class, for example `bi-inbox`. */
  icon?: string
}

/** Standard "nothing here yet" panel: an empty result set, an unselected company, or a
 * module that is not built yet. */
export function EmptyState({ title, message, action, icon = 'bi-inbox' }: EmptyStateProps) {
  return (
    <div className="tms-empty">
      <div className="tms-empty-icon">
        <i className={`bi ${icon}`} aria-hidden="true" />
      </div>
      <p className="mb-1 fw-semibold text-body">{title}</p>
      {message && <p className="mb-3 small">{message}</p>}
      {action}
    </div>
  )
}
