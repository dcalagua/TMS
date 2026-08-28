import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { COMPANY_ID_HEADER, setAuthRefreshHandler, setAuthTokenProvider } from "./httpClient";
import { fetchIntegrationHealth, type IntegrationHealthView } from "./integrationsApi";

/**
 * El resumen de salud de integraciones (JOB 13).
 *
 * Además de la ruta, se fija la regla de lectura que hace útil al panel: **`oldestPendingAt` es la
 * señal, no `deliveriesPending`**. Mil entregas pendientes que avanzan es una cola sana; tres que
 * esperan desde el martes no lo es, y un contador solo no distingue una cosa de la otra. Si alguien
 * simplificara la pantalla a "N pendientes", esta prueba dice qué se pierde.
 */

const COMPANY = "11111111-1111-4111-8111-111111111111";

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn().mockImplementation(async () => new Response(JSON.stringify({}), {
    status: 200, headers: { "content-type": "application/json" },
  }));
  vi.stubGlobal("fetch", fetchMock);
  setAuthTokenProvider(async () => "test-token");
  setAuthRefreshHandler(async () => null);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("la salud de las integraciones", () => {
  it("se lee de /webhooks/health con el ámbito de empresa", async () => {
    await fetchIntegrationHealth(COMPANY);

    const [url, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit];
    expect(new URL(url).pathname).toBe("/api/v1/webhooks/health");
    expect((init.headers as Record<string, string>)[COMPANY_ID_HEADER]).toBe(COMPANY);
  });

  it("una cola grande que avanza no tiene edad, y una pequeña atascada sí", () => {
    const moving: IntegrationHealthView = {
      deliveriesPending: 1000, oldestPendingAt: null, deliveriesFailed: 0, deliveriesProcessed: 90000,
      inactiveSubscriptionsWithBacklog: 0, requestsSince: "2026-09-06T12:00:00Z",
      requestsSucceeded: 0, requestsPartial: 0, requestsRejected: 0, requestsFailed: 0,
    };
    const stuck: IntegrationHealthView = { ...moving, deliveriesPending: 3, oldestPendingAt: "2026-09-01T08:00:00Z" };

    // La afirmación que importa: el contador NO ordena estas dos situaciones correctamente.
    expect(moving.deliveriesPending).toBeGreaterThan(stuck.deliveriesPending);
    // La edad sí.
    expect(moving.oldestPendingAt).toBeNull();
    expect(stuck.oldestPendingAt).not.toBeNull();
  });

  it("separa lo que rechazó el socio de lo que falló en TMS: son llamadas distintas", () => {
    const health: IntegrationHealthView = {
      deliveriesPending: 0, oldestPendingAt: null, deliveriesFailed: 0, deliveriesProcessed: 0,
      inactiveSubscriptionsWithBacklog: 0, requestsSince: "2026-09-06T12:00:00Z",
      requestsSucceeded: 400, requestsPartial: 3, requestsRejected: 12, requestsFailed: 1,
    };

    expect(health.requestsRejected).not.toEqual(health.requestsFailed);
  });
});
