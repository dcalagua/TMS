import type { ReactNode } from 'react'

/** Small uppercase heading that separates groups of fields inside a form or panel. Renders a
 * real heading element so the document outline reflects the grouping. */
export function SectionHeader({ title, actions, level = 3 }: {
  title: string
  actions?: ReactNode
  level?: 2 | 3 | 4
}) {
  const Heading = `h${level}` as const

  return (
    <div className="d-flex align-items-center justify-content-between gap-2 mb-2">
      <Heading className="tms-section-title mb-0">{title}</Heading>
      {actions}
    </div>
  )
}
