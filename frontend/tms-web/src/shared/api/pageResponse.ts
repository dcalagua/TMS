/** Mirrors the backend's `PageResponse<T>` envelope (`docs/api/API_CONVENTIONS.md` section 5).
 * `totalPages`/`hasNext`/`hasPrevious` are derived client-side the same way the backend
 * derives them, so a page that only sends the four raw fields still works. */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
}

export function totalPages(response: Pick<PageResponse<unknown>, 'size' | 'totalElements'>): number {
  return response.size <= 0 ? 0 : Math.ceil(response.totalElements / response.size)
}

export function hasNextPage(response: Pick<PageResponse<unknown>, 'page' | 'size' | 'totalElements'>): boolean {
  return (response.page + 1) * response.size < response.totalElements
}

export function hasPreviousPage(response: Pick<PageResponse<unknown>, 'page'>): boolean {
  return response.page > 0
}
