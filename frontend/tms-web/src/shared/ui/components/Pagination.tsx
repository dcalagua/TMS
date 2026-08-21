import { useTranslation } from 'react-i18next'
import { hasNextPage, hasPreviousPage, totalPages, type PageResponse } from '../../api/pageResponse'
import { useFormat } from '../../i18n/format'
import { Select } from './Select'

/** Offered sizes. Kept small and round: a page size is a comfort setting, not a query. */
export const PAGE_SIZE_OPTIONS = [10, 20, 25, 50, 100]

interface PaginationProps {
  page: Pick<PageResponse<unknown>, 'page' | 'size' | 'totalElements'>
  onPageChange: (page: number) => void
  /** Enables the rows-per-page control. Omit it and the control is not rendered, which is what
   * a screen with a fixed page size wants. */
  onPageSizeChange?: (size: number) => void
  sizeOptions?: number[]
}

/**
 * The table's footer strip: how many rows per page, which rows are on screen, and the two
 * controls that move between pages.
 *
 * It replaced a numbered Bootstrap pager. Numbered pages answer "which page am I on", which is
 * rarely what an operator wants to know; "1-25 of 340" answers "how much is there and how far
 * in am I", and the page size is the setting they actually reach for when a list is long.
 *
 * Rendered whenever there is anything at all, even on a single page: the range and the size
 * control are useful there too, and a footer that appears and disappears as results cross the
 * page boundary makes the panel jump.
 */
export function Pagination({ page, onPageChange, onPageSizeChange, sizeOptions = PAGE_SIZE_OPTIONS }: PaginationProps) {
  const { t } = useTranslation('common')
  const format = useFormat()
  const pages = totalPages(page)

  if (page.totalElements === 0) {
    return null
  }

  const current = page.page
  const from = current * page.size + 1
  const to = Math.min((current + 1) * page.size, page.totalElements)
  const canPrevious = hasPreviousPage(page)
  const canNext = hasNextPage(page)

  return (
    <nav className="tms-pager" aria-label={t('pagination.label')}>
      {onPageSizeChange && (
        <div className="tms-pager-size">
          <span className="tms-pager-size-label" id="tms-pager-size-label">
            {t('pagination.rowsPerPage')}
          </span>
          <Select
            size="sm"
            value={String(page.size)}
            onChange={(next) => onPageSizeChange(Number(next))}
            options={sizeOptions.map((size) => ({ value: String(size), label: format.quantity(size) }))}
            aria-describedby="tms-pager-size-label"
          />
        </div>
      )}

      <span className="tms-pager-range">
        {t('pagination.range', {
          from: format.quantity(from),
          to: format.quantity(to),
          total: format.quantity(page.totalElements),
        })}
      </span>

      <div className="tms-pager-steps">
        <button
          type="button"
          className="tms-pager-step"
          onClick={() => onPageChange(current - 1)}
          disabled={!canPrevious}
          aria-label={t('pagination.previousPage')}
          title={t('pagination.previousPage')}
        >
          <i className="bi bi-chevron-left" aria-hidden="true" />
        </button>
        {/* The page counter is only worth the room once there is more than one page. */}
        {pages > 1 && (
          <span className="tms-pager-count">
            {t('pagination.pageOf', { page: format.quantity(current + 1), pages: format.quantity(pages) })}
          </span>
        )}
        <button
          type="button"
          className="tms-pager-step"
          onClick={() => onPageChange(current + 1)}
          disabled={!canNext}
          aria-label={t('pagination.nextPage')}
          title={t('pagination.nextPage')}
        >
          <i className="bi bi-chevron-right" aria-hidden="true" />
        </button>
      </div>
    </nav>
  )
}
