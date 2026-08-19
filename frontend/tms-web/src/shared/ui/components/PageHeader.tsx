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
      {actions && <div className="d-flex align-items-center gap-2 flex-shrink-0">{actions}</div>}
    </div>
  )
}
