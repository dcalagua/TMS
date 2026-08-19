import type { ReactNode } from 'react'

interface EmptyStateProps {
  title: string
  message?: string
  action?: ReactNode
  icon?: ReactNode
}

/** Standard "nothing here yet" panel: an empty result set, an unselected company, or a
 * module that is not built yet. */
export function EmptyState({ title, message, action, icon }: EmptyStateProps) {
  return (
    <div className="text-center py-5 text-body-secondary">
      {icon && <div className="mb-2 fs-2">{icon}</div>}
      <p className="mb-1 fw-semibold text-body">{title}</p>
      {message && <p className="mb-3 small">{message}</p>}
      {action}
    </div>
  )
}
