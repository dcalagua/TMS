import { useTranslation } from 'react-i18next'
import { hasNextPage, hasPreviousPage, totalPages, type PageResponse } from '../../api/pageResponse'
import { useFormat } from '../../i18n/format'

interface PaginationProps {
  page: Pick<PageResponse<unknown>, 'page' | 'size' | 'totalElements'>
  onPageChange: (page: number) => void
}

/** Paging control driven directly by the backend's page envelope - zero-based, server-clamped
 * `size`, `totalElements` scoped to the caller's company (`API_CONVENTIONS.md` section 5). */
export function Pagination({ page, onPageChange }: PaginationProps) {
  const { t } = useTranslation('common')
  const format = useFormat()
  const pages = totalPages(page)

  if (pages <= 1) {
    return null
  }

  const current = page.page
  const from = page.totalElements === 0 ? 0 : current * page.size + 1
  const to = Math.min((current + 1) * page.size, page.totalElements)

  return (
    <nav className="d-flex flex-wrap align-items-center justify-content-between gap-2" aria-label={t('pagination.label')}>
      <span className="small text-body-secondary">
        {t('pagination.range', {
          from: format.quantity(from),
          to: format.quantity(to),
          total: format.quantity(page.totalElements),
        })}
      </span>
      <ul className="pagination pagination-sm mb-0">
        <li className={`page-item${hasPreviousPage(page) ? '' : ' disabled'}`}>
          <button
            type="button"
            className="page-link"
            onClick={() => onPageChange(current - 1)}
            disabled={!hasPreviousPage(page)}
          >
            {t('actions.previous')}
          </button>
        </li>
        <li className="page-item disabled">
          <span className="page-link">
            {t('pagination.pageOf', { page: format.quantity(current + 1), pages: format.quantity(pages) })}
          </span>
        </li>
        <li className={`page-item${hasNextPage(page) ? '' : ' disabled'}`}>
          <button
            type="button"
            className="page-link"
            onClick={() => onPageChange(current + 1)}
            disabled={!hasNextPage(page)}
          >
            {t('actions.next')}
          </button>
        </li>
      </ul>
    </nav>
  )
}
