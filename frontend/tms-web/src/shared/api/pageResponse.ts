/** Refleja el sobre `PageResponse<T>` del backend (API_CONVENTIONS §5). `totalPages`,
 * `hasNext` y `hasPrevious` se derivan en cliente igual que los deriva el backend, de modo
 * que una página que solo mande los cuatro campos crudos sigue funcionando. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}

export function totalPages(response: Pick<PageResponse<unknown>, "size" | "totalElements">): number {
  return response.size <= 0 ? 0 : Math.ceil(response.totalElements / response.size);
}

export function hasNextPage(response: Pick<PageResponse<unknown>, "page" | "size" | "totalElements">): boolean {
  return (response.page + 1) * response.size < response.totalElements;
}

export function hasPreviousPage(response: Pick<PageResponse<unknown>, "page">): boolean {
  return response.page > 0;
}

/** Una página vacía, para el estado inicial de una pantalla que aún no ha respondido. */
export function emptyPage<T>(size: number): PageResponse<T> {
  return { content: [], page: 0, size, totalElements: 0 };
}
