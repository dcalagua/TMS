import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { EmptyState } from './EmptyState'
import { ErrorState } from './ErrorState'
import { SkeletonTable } from './Skeleton'

export interface DataTableColumn<T> {
  key: string
  header: string
  render: (row: T) => ReactNode
  className?: string
  /** Right-aligns and tabular-aligns the column: weights, volumes, counts. */
  numeric?: boolean
  /** Pins the column to the right and shrinks it: the row's action controls. */
  actions?: boolean
}

interface DataTableProps<T> {
  columns: DataTableColumn<T>[]
  rows: T[]
  rowKey: (row: T) => string
  isLoading?: boolean
  error?: string | null
  onRetry?: () => void
  emptyTitle?: string
  emptyMessage?: string
  emptyAction?: ReactNode
  /** Caption for assistive technology; the table is otherwise unnamed. */
  caption?: string
}

function columnClass<T>(column: DataTableColumn<T>): string | undefined {
  const classes = [column.className, column.numeric ? 'tms-cell-numeric' : null, column.actions ? 'tms-cell-actions' : null]
    .filter(Boolean)
    .join(' ')
  return classes || undefined
}

/**
 * Table wrapper every master/list screen builds on: one place that owns loading, error and
 * empty presentation, operational row density, the horizontal scroll container and column
 * definitions as data rather than JSX repeated per screen.
 *
 * The scroll lives on the table's own wrapper. A wide table must never widen the page - that
 * is what puts a horizontal scrollbar on the whole application at tablet widths.
 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  isLoading = false,
  error = null,
  onRetry,
  emptyTitle,
  emptyMessage,
  emptyAction,
  caption,
}: DataTableProps<T>) {
  const { t } = useTranslation('common')

  if (error) {
    return (
      <div className="tms-table-wrap p-3">
        <ErrorState message={error} onRetry={onRetry} />
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className="tms-table-wrap">
        <p className="visually-hidden" role="status">
          {t('states.loadingRecords')}
        </p>
        <SkeletonTable columns={Math.min(columns.length, 6)} />
      </div>
    )
  }

  if (rows.length === 0) {
    return (
      <div className="tms-table-wrap">
        <EmptyState title={emptyTitle ?? t('states.noRecords')} message={emptyMessage} action={emptyAction} />
      </div>
    )
  }

  return (
    <div className="tms-table-wrap">
      <div className="tms-table-scroll">
        <table className="table table-hover tms-table align-middle">
          {caption && <caption className="visually-hidden">{caption}</caption>}
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column.key} scope="col" className={columnClass(column)}>
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={rowKey(row)}>
                {columns.map((column) => (
                  <td key={column.key} className={columnClass(column)}>
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
