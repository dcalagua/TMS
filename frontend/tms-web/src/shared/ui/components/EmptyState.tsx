import type { ReactNode } from 'react'

interface EmptyStateProps {
  title: string
  message?: string
  action?: ReactNode
  /** Bootstrap Icons class, for example `bi-inbox`. */
  icon?: string
}

/**
 * The "nothing here" panel: an empty result set, an unselected company, or a module that is not
 * built yet.
 *
 * It is composed rather than apologetic - a framed mark, a heading in body colour, one line
 * saying what to do next, and the control that does it. A small grey glyph adrift in a tall
 * blank box reads as a page that failed to load, which is exactly the wrong message for a list
 * that has simply been filtered down to nothing.
 */
export function EmptyState({ title, message, action, icon = 'bi-inbox' }: EmptyStateProps) {
  return (
    <div className="tms-empty">
      <span className="tms-empty-icon" aria-hidden="true">
        <i className={`bi ${icon}`} />
      </span>
      <p className="tms-empty-title">{title}</p>
      {message && <p className="tms-empty-message">{message}</p>}
      {action && <div className="tms-empty-action">{action}</div>}
    </div>
  )
}
