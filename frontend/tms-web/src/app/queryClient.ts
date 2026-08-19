import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../shared/api/httpClient'

/**
 * Shared TanStack Query configuration.
 *
 * Authorization failures are never retried: a 401/403 means the backend refused the
 * caller, and hammering the endpoint would only produce noise.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => {
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
            return false
          }
          return failureCount < 2
        },
      },
    },
  })
}
