import { hasNextPage, hasPreviousPage, totalPages, type PageResponse } from '../../api/pageResponse'

interface PaginationProps {
  page: Pick<PageResponse<unknown>, 'page' | 'size' | 'totalElements'>
  onPageChange: (page: number) => void
}

/** Paging control driven directly by the backend's page envelope - zero-based, server-clamped
 * `size`, `totalElements` scoped to the caller's company (`API_CONVENTIONS.md` section 5). */
export function Pagination({ page, onPageChange }: PaginationProps) {
  const pages = totalPages(page)
  if (pages <= 1) {
    return null
  }

  const current = page.page
  const from = page.totalElements === 0 ? 0 : current * page.size + 1
  const to = Math.min((current + 1) * page.size, page.totalElements)

  return (
    <nav className="d-flex flex-wrap align-items-center justify-content-between gap-2" aria-label="Pagination">
      <span className="small text-body-secondary">
        {from}-{to} of {page.totalElements}
      </span>
      <ul className="pagination pagination-sm mb-0">
        <li className={`page-item${hasPreviousPage(page) ? '' : ' disabled'}`}>
          <button type="button" className="page-link" onClick={() => onPageChange(current - 1)} disabled={!hasPreviousPage(page)}>
            Previous
          </button>
        </li>
        <li className="page-item disabled">
          <span className="page-link">
            Page {current + 1} of {pages}
          </span>
        </li>
        <li className={`page-item${hasNextPage(page) ? '' : ' disabled'}`}>
          <button type="button" className="page-link" onClick={() => onPageChange(current + 1)} disabled={!hasNextPage(page)}>
            Next
          </button>
        </li>
      </ul>
    </nav>
  )
}
