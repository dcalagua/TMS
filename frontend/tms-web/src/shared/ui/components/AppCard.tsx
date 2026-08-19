import type { ReactNode } from 'react'

export interface AppCardProps {
  title?: ReactNode
  actions?: ReactNode
  children: ReactNode
  /** Drops the body padding for a card whose content brings its own (a table, a list). */
  flush?: boolean
  className?: string
}

/** The product's panel. One place owns the surface, border, radius and elevation, so panels
 * cannot drift apart screen by screen. */
export function AppCard({ title, actions, children, flush = false, className }: AppCardProps) {
  return (
    <div className={`tms-card${className ? ` ${className}` : ''}`}>
      {(title || actions) && (
        <div className="tms-card-header">
          <span className="tms-min-w-0 tms-truncate">{title}</span>
          {actions && <span className="d-flex align-items-center gap-2 flex-none">{actions}</span>}
        </div>
      )}
      <div className={flush ? '' : 'tms-card-body'}>{children}</div>
    </div>
  )
}
