import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "../shared/api/httpClient";

/**
 * Configuración compartida de TanStack Query.
 *
 * Los fallos de autorización no se reintentan nunca: un 401/403 significa que el backend
 * rechazó a quien llama, y machacar el endpoint solo produciría ruido.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => {
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
            return false;
          }
          return failureCount < 2;
        },
      },
    },
  });
}
