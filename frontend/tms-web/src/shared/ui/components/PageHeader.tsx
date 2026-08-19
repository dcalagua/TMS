import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  description?: string
  /** Short contextual facts shown next to the title - a result count, a date, a status. */
  meta?: ReactNode
  actions?: ReactNode
}

/** Consistent page title row used at the top of every screen: a title, optional description
 * text, optional inline context, and right-aligned actions. */
export function PageHeader({ title, description, meta, actions }: PageHeaderProps) {
  return (
    <div className="d-flex flex-wrap align-items-start justify-content-between gap-3 mb-3">
      <div className="tms-min-w-0">
        <div className="d-flex flex-wrap align-items-center gap-2">
          <h1 className="tms-page-title">{title}</h1>
          {meta}
        </div>
        {description && <p className="tms-page-subtitle">{description}</p>}
      </div>
      {/* Wraps rather than refusing to shrink: three action buttons on a 320px screen must
          fall onto a second line, not push the page into a horizontal scrollbar. */}
      {actions && <div className="d-flex flex-wrap align-items-center gap-2">{actions}</div>}
    </div>
  )
}
