import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  COMPANY_ID_HEADER,
  CORRELATION_ID_HEADER,
  apiRequest,
  isAuthFailureResponse,
  setAuthRefreshHandler,
  setAuthTokenProvider,
} from "./httpClient";

/**
 * El cliente HTTP: cómo construye una petición y cómo traduce una respuesta de error.
 *
 * Es la utilidad más crítica del frontend porque toda pantalla pasa por ella. Un fallo aquí no
 * se ve como un fallo: se ve como una pantalla vacía, o como una fuga entre empresas si la
 * cabecera de ámbito deja de viajar. Se prueba contra un `fetch` sustituido en lugar de contra
 * un servidor, para que lo que se afirme sea exactamente la petición que sale.
 */

const BASE = "http://localhost:8080/api/v1";

function jsonResponse(body: unknown, init: { status?: number; headers?: Record<string, string> } = {}): Response {
  return new Response(JSON.stringify(body), {
    status: init.status ?? 200,
    headers: { "content-type": "application/json", ...(init.headers ?? {}) },
  });
}

/** La última petición que el `fetch` sustituido recibió, desmontada en algo afirmable. */
function lastCall(fetchMock: ReturnType<typeof vi.fn>) {
  const [url, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit];
  return { url: new URL(url), init, headers: (init.headers ?? {}) as Record<string, string> };
}

describe("apiRequest", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    setAuthTokenProvider(async () => null);
    setAuthRefreshHandler(async () => null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe("construcción de la petición", () => {
    it("resuelve el path contra la base del API y devuelve el cuerpo ya parseado", async () => {
      fetchMock.mockResolvedValue(jsonResponse({ content: [], totalElements: 0 }));

      const payload = await apiRequest<{ totalElements: number }>("/masterdata/locations");

      expect(lastCall(fetchMock).url.toString()).toBe(`${BASE}/masterdata/locations`);
      expect(payload.totalElements).toBe(0);
    });

    it("acepta un path sin barra inicial sin duplicarla ni perderla", async () => {
      fetchMock.mockResolvedValue(jsonResponse({}));

      await apiRequest("masterdata/zones");

      expect(lastCall(fetchMock).url.pathname).toBe("/api/v1/masterdata/zones");
    });

    it("serializa la query y omite los valores nulos en vez de mandarlos vacíos", async () => {
      fetchMock.mockResolvedValue(jsonResponse({}));

      await apiRequest("/masterdata/locations", {
        query: { page: 0, size: 25, role: "ORIGIN", search: undefined, zoneId: null, active: true },
      });

      const { url } = lastCall(fetchMock);
      expect(url.searchParams.get("page")).toBe("0");
      expect(url.searchParams.get("size")).toBe("25");
      expect(url.searchParams.get("role")).toBe("ORIGIN");
      expect(url.searchParams.get("active")).toBe("true");
      // Un `search=` vacío no es lo mismo que no filtrar: el backend lo trataría como un filtro.
      expect(url.searchParams.has("search")).toBe(false);
      expect(url.searchParams.has("zoneId")).toBe(false);
    });

    it("envía el ámbito de empresa y el bearer token como cabeceras", async () => {
      setAuthTokenProvider(async () => "token-abc");
      fetchMock.mockResolvedValue(jsonResponse({}));

      await apiRequest("/masterdata/locations", { companyId: "company-1" });

      const { headers } = lastCall(fetchMock);
      expect(headers[COMPANY_ID_HEADER]).toBe("company-1");
      expect(headers.Authorization).toBe("Bearer token-abc");
      expect(headers[CORRELATION_ID_HEADER]).toEqual(expect.any(String));
    });

    it("no inventa una cabecera de empresa cuando el endpoint no tiene ámbito", async () => {
      fetchMock.mockResolvedValue(jsonResponse({}));

      await apiRequest("/system/info");

      expect(lastCall(fetchMock).headers[COMPANY_ID_HEADER]).toBeUndefined();
    });

    it("manda el cuerpo como JSON y declara su Content-Type solo cuando hay cuerpo", async () => {
      fetchMock.mockResolvedValue(jsonResponse({ id: "new-1" }, { status: 201 }));

      await apiRequest("/masterdata/zones", { method: "POST", body: { code: "NORTE" } });

      const { init, headers } = lastCall(fetchMock);
      expect(init.method).toBe("POST");
      expect(headers["Content-Type"]).toBe("application/json");
      expect(init.body).toBe(JSON.stringify({ code: "NORTE" }));
    });

    it("una petición GET no declara Content-Type porque no lleva cuerpo", async () => {
      fetchMock.mockResolvedValue(jsonResponse({}));

      await apiRequest("/orders");

      expect(lastCall(fetchMock).headers["Content-Type"]).toBeUndefined();
    });
  });

  describe("traducción de Problem Details (RFC 9457)", () => {
    it("convierte un problem+json en un ApiError que conserva status, code y correlationId", async () => {
      fetchMock.mockResolvedValue(
        jsonResponse(
          {
            type: "urn:tms:problem:resource-not-found",
            title: "Resource not found",
            status: 404,
            detail: "No existe la ubicación pedida.",
            code: "resource-not-found",
            correlationId: "corr-123",
          },
          { status: 404 },
        ),
      );

      const error = await apiRequest("/masterdata/locations/missing").catch((caught: unknown) => caught);

      expect(error).toBeInstanceOf(ApiError);
      const apiError = error as ApiError;
      expect(apiError.status).toBe(404);
      expect(apiError.code).toBe("resource-not-found");
      expect(apiError.correlationId).toBe("corr-123");
    });

    it("expone los errores de campo de una validación fallida", async () => {
      fetchMock.mockResolvedValue(
        jsonResponse(
          {
            status: 400,
            code: "validation-failed",
            errors: [{ field: "code", message: "must not be blank" }],
          },
          { status: 400 },
        ),
      );

      const error = (await apiRequest("/masterdata/locations", { method: "POST", body: {} }).catch(
        (caught: unknown) => caught,
      )) as ApiError;

      expect(error.code).toBe("validation-failed");
      expect(error.fieldErrors).toEqual([{ field: "code", message: "must not be blank" }]);
    });

    it("un error sin cuerpo Problem Details sigue siendo un ApiError con su status", async () => {
      fetchMock.mockResolvedValue(new Response("<html>502</html>", { status: 502 }));

      const error = (await apiRequest("/orders").catch((caught: unknown) => caught)) as ApiError;

      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(502);
      // Sin documento no hay código estable: null es lo honesto, y la UI cae en su copy genérico.
      expect(error.code).toBeNull();
    });
  });

  describe("refresco de sesión", () => {
    it("reintenta una vez cuando el token se refrescó, y devuelve el resultado del reintento", async () => {
      setAuthTokenProvider(async () => "stale");
      setAuthRefreshHandler(async () => "fresh");
      fetchMock
        .mockResolvedValueOnce(jsonResponse({ status: 401, code: "invalid-token" }, { status: 401 }))
        .mockResolvedValueOnce(jsonResponse({ totalElements: 3 }));

      const payload = await apiRequest<{ totalElements: number }>("/orders", { companyId: "company-1" });

      expect(payload.totalElements).toBe(3);
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(lastCall(fetchMock).headers.Authorization).toBe("Bearer fresh");
    });

    it("no entra en bucle cuando el refresco no produce un token distinto", async () => {
      setAuthTokenProvider(async () => "stale");
      setAuthRefreshHandler(async () => "stale");
      fetchMock.mockResolvedValue(jsonResponse({ status: 401, code: "invalid-token" }, { status: 401 }));

      await expect(apiRequest("/orders")).rejects.toBeInstanceOf(ApiError);
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });
  });
});

describe("isAuthFailureResponse", () => {
  it("reconoce los dos códigos de token inservible y el 401 pelado", () => {
    expect(isAuthFailureResponse(401, "invalid-token")).toBe(true);
    expect(isAuthFailureResponse(401, "unauthenticated")).toBe(true);
    expect(isAuthFailureResponse(401, null)).toBe(true);
  });

  it("no confunde un 403 de permisos con un fallo de autenticación", () => {
    // Refrescar la sesión no arregla un permiso que no se tiene: reintentar sería ruido.
    expect(isAuthFailureResponse(403, "access-denied")).toBe(false);
  });
});
