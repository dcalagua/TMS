import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { COMPANY_ID_HEADER, setAuthRefreshHandler, setAuthTokenProvider } from "./httpClient";
import { fetchControlTower, fetchControlTowerTrips } from "./controlTowerApi";
import { downloadKpiCsv, fetchKpiReport } from "./reportingApi";
import { fetchLocation, fetchLocations } from "./locationsApi";
import { fetchDestinations } from "./destinationsApi";
import { fetchOrigins } from "./originsApi";

/**
 * Los endpoints de los tres módulos que el gate de runtime vigila — Torre de control, Reportes y
 * KPIs, y Ubicaciones — más las dos proyecciones de Ubicaciones.
 *
 * Lo que se protege aquí es la parte del contrato que ningún typechecker ve: la ruta literal y
 * los parámetros que salen. Renombrar una ruta en el backend, o dejar de mandar `role`, compila
 * perfectamente y se manifiesta como una pantalla vacía o como la lista equivocada.
 */

const BASE = "http://localhost:8080/api/v1";
const COMPANY = "11111111-1111-4111-8111-111111111111";

let fetchMock: ReturnType<typeof vi.fn>;

function ok(body: unknown = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function sent() {
  const [url, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit];
  return { url: new URL(url), headers: (init.headers ?? {}) as Record<string, string> };
}

beforeEach(() => {
  // Una fábrica, no un valor: el cuerpo de un Response solo puede leerse una vez, así que un
  // único objeto compartido rompe el segundo `fetch` de cualquier test que haga dos llamadas.
  fetchMock = vi.fn().mockImplementation(async () => ok({ content: [], totalElements: 0 }));
  vi.stubGlobal("fetch", fetchMock);
  setAuthTokenProvider(async () => "test-token");
  setAuthRefreshHandler(async () => null);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("Ubicaciones", () => {
  it("lista contra /masterdata/locations con el ámbito de empresa", async () => {
    await fetchLocations({ companyId: COMPANY, page: 0, size: 25 });

    const { url, headers } = sent();
    expect(url.pathname).toBe("/api/v1/masterdata/locations");
    expect(headers[COMPANY_ID_HEADER]).toBe(COMPANY);
  });

  it("lee una ubicación por id", async () => {
    fetchMock.mockResolvedValue(ok({ id: "loc-1" }));

    await fetchLocation(COMPANY, "loc-1");

    expect(sent().url.pathname).toBe("/api/v1/masterdata/locations/loc-1");
  });

  it("Orígenes y Destinos son la misma colección filtrada por rol, no endpoints propios", async () => {
    // Es el punto del modelo canónico: un lugar físico, dos usos operativos. Si esto dejara de
    // mandar `role`, ambas pantallas mostrarían en silencio todas las ubicaciones.
    await fetchOrigins({ companyId: COMPANY });
    const origins = sent();

    await fetchDestinations({ companyId: COMPANY });
    const destinations = sent();

    expect(origins.url.pathname).toBe("/api/v1/masterdata/locations");
    expect(origins.url.searchParams.get("role")).toBe("ORIGIN");
    expect(destinations.url.pathname).toBe("/api/v1/masterdata/locations");
    expect(destinations.url.searchParams.get("role")).toBe("DESTINATION");
  });
});

describe("Torre de control", () => {
  it("pide el resumen del día a /monitoring/control-tower", async () => {
    fetchMock.mockResolvedValue(ok({ date: "2026-08-25", summary: {} }));

    await fetchControlTower({ companyId: COMPANY, date: "2026-08-25" });

    const { url, headers } = sent();
    expect(url.pathname).toBe("/api/v1/monitoring/control-tower");
    expect(url.searchParams.get("date")).toBe("2026-08-25");
    expect(headers[COMPANY_ID_HEADER]).toBe(COMPANY);
  });

  it("pide el tablero de viajes a /monitoring/control-tower/trips conservando fecha y paginación", async () => {
    await fetchControlTowerTrips({ companyId: COMPANY, date: "2026-08-25", page: 2, size: 50 });

    const { url } = sent();
    expect(url.pathname).toBe("/api/v1/monitoring/control-tower/trips");
    expect(url.searchParams.get("date")).toBe("2026-08-25");
    expect(url.searchParams.get("page")).toBe("2");
    expect(url.searchParams.get("size")).toBe("50");
  });
});

describe("Reportes y KPIs", () => {
  it("pide el agregado a /reporting/kpis con el rango pedido", async () => {
    fetchMock.mockResolvedValue(ok({ from: "2026-08-01", to: "2026-08-25", days: 25 }));

    await fetchKpiReport({ companyId: COMPANY, from: "2026-08-01", to: "2026-08-25" });

    const { url } = sent();
    expect(url.pathname).toBe("/api/v1/reporting/kpis");
    expect(url.searchParams.get("from")).toBe("2026-08-01");
    expect(url.searchParams.get("to")).toBe("2026-08-25");
  });

  it("omite el rango cuando no se da, para que lo decida el servidor en la zona de la empresa", async () => {
    fetchMock.mockResolvedValue(ok({ days: 30 }));

    await fetchKpiReport({ companyId: COMPANY });

    const { url } = sent();
    // Mandar `from=` vacío haría al servidor interpretar un rango en vez de aplicar su defecto.
    expect(url.searchParams.has("from")).toBe(false);
    expect(url.searchParams.has("to")).toBe(false);
  });

  it("descarga el CSV desde /reporting/kpis/export y conserva el nombre que da el servidor", async () => {
    fetchMock.mockResolvedValue(
      new Response("date,trips\n", {
        status: 200,
        headers: {
          "content-type": "text/csv",
          "content-disposition": 'attachment; filename="kpis-2026-08.csv"',
        },
      }),
    );

    const file = await downloadKpiCsv({ companyId: COMPANY, from: "2026-08-01", to: "2026-08-25" });

    expect(sent().url.pathname).toBe("/api/v1/reporting/kpis/export");
    expect(file.fileName).toBe("kpis-2026-08.csv");
  });

  it("una descarga que falla entrega el problema, no un blob opaco", async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ status: 403, code: "access-denied" }), {
        status: 403,
        headers: { "content-type": "application/problem+json" },
      }),
    );

    await expect(downloadKpiCsv({ companyId: COMPANY })).rejects.toMatchObject({
      status: 403,
      code: "access-denied",
    });
  });
});

describe("prefijo del API", () => {
  it("toda ruta cuelga de la base configurada, sin barras duplicadas", async () => {
    await fetchLocations({ companyId: COMPANY });

    expect(sent().url.toString().startsWith(`${BASE}/`)).toBe(true);
    expect(sent().url.pathname).not.toContain("//");
  });
});
