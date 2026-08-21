import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useFormat } from '../../i18n/format'
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
  /**
   * Server-side total for the current filters. Shown as a persistent count above the table -
   * unlike the pager, which hides itself when everything fits on one page.
   */
  total?: number
  /**
   * Rendered on the panel's own footer strip, below the last row. The pager belongs here rather
   * than in a card wrapped around this one: a panel inside a panel doubles the border and the
   * radius, which is what made the old list screens read as boxes stacked on a grey field.
   */
  footer?: ReactNode
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
  total,
  footer,
}: DataTableProps<T>) {
  const { t } = useTranslation('common')
  const format = useFormat()

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

  const isEmpty = rows.length === 0

  return (
    <div className="tms-table-wrap">
      {total !== undefined && (
        <p className="tms-result-bar mb-0">
          <span className="tms-result-count">{format.quantity(total)}</span>
          <span>{t('table.results', { count: total })}</span>
        </p>
      )}
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
            {isEmpty ? (
              /* The empty message goes inside the table rather than replacing it. Swapping the
                 whole panel for a message removes the column headers - the thing that tells the
                 user what this list is - and makes the screen appear to change identity between
                 one search and the next. */
              <tr className="tms-table-empty-row">
                <td colSpan={columns.length}>
                  <EmptyState
                    title={emptyTitle ?? t('states.noRecords')}
                    message={emptyMessage}
                    action={emptyAction}
                  />
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr key={rowKey(row)}>
                  {columns.map((column) => (
                    <td key={column.key} className={columnClass(column)}>
                      {column.render(row)}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      {footer && <div className="tms-table-foot">{footer}</div>}
    </div>
  )
}
