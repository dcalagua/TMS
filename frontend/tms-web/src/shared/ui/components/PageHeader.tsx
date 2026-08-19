import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  description?: string
  actions?: ReactNode
}

/** Consistent page title row used at the top of every screen: a title, optional description
 * text, and right-aligned actions (create button, export, ...). */
export function PageHeader({ title, description, actions }: PageHeaderProps) {
  return (
    <div className="d-flex flex-wrap align-items-start justify-content-between gap-2 mb-3">
      <div>
        <h1 className="h4 mb-0">{title}</h1>
        {description && <p className="text-body-secondary small mb-0">{description}</p>}
      </div>
      {actions && <div className="d-flex align-items-center gap-2">{actions}</div>}
    </div>
  )
}
