/** Placeholder block shown while a panel's data loads, for layouts stable enough that the
 * skeleton matches what arrives. Where it would not, a spinner is honest and a skeleton is a
 * guess, so `LoadingState` stays the default. */
export function Skeleton({ width = '100%', height = '1rem', className }: {
  width?: string
  height?: string
  className?: string
}) {
  return <span className={`tms-skeleton d-block${className ? ` ${className}` : ''}`} style={{ width, height }} />
}

/** Rows of skeleton cells approximating a table that is still loading. */
export function SkeletonTable({ rows = 5, columns = 4 }: { rows?: number; columns?: number }) {
  return (
    <div className="p-3" aria-hidden="true">
      {Array.from({ length: rows }, (_unused, row) => (
        <div key={row} className="d-flex gap-3 mb-2">
          {Array.from({ length: columns }, (_column, index) => (
            <Skeleton key={index} height="0.875rem" width={index === 0 ? '18%' : '26%'} />
          ))}
        </div>
      ))}
    </div>
  )
}
