import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  /** bootstrap-icons name, without the `bi-` prefix: the module's identity tile. */
  icon?: string
  description?: string
  /** Short contextual facts shown next to the title - a result count, a date, a status. */
  meta?: ReactNode
  actions?: ReactNode
}

/**
 * The title row every screen opens with: an identity tile, the title, one line of description,
 * optional inline context, and the screen's actions on the right.
 *
 * The tile is decorative and marked `aria-hidden`: the heading already names the page, so
 * announcing an icon would only add noise. What it buys is a fixed visual anchor at the top
 * left of every screen, which is what makes eight different lists feel like one product.
 */
export function PageHeader({ title, icon, description, meta, actions }: PageHeaderProps) {
  return (
    <div className="tms-page-head">
      <div className="d-flex align-items-start gap-3 tms-min-w-0">
        {icon && (
          <span className="tms-page-tile" aria-hidden="true">
            <i className={`bi bi-${icon}`} />
          </span>
        )}
        <div className="tms-min-w-0">
          <div className="d-flex flex-wrap align-items-center gap-2">
            <h1 className="tms-page-title">{title}</h1>
            {meta}
          </div>
          {description && <p className="tms-page-subtitle">{description}</p>}
        </div>
      </div>
      {actions && <div className="tms-page-actions">{actions}</div>}
    </div>
  )
}
