import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { COMPANY_ID_HEADER, setAuthRefreshHandler, setAuthTokenProvider } from "./httpClient";
import { recomputeTripEta } from "./planningApi";
import { setLocationGeofence } from "./locationsApi";

/**
 * El contrato de la ETA por parada y del geocerco (migración V43, ADR-011).
 *
 * Se protege lo que ningún typechecker ve: la ruta literal, el verbo, y que un radio nulo viaje en
 * el cuerpo. Ese último punto no es cosmético — `null` **borra** el geocerco, y mandarlo como
 * parámetro de consulta haría indistinguible "quítalo" de "no lo mandé", que es exactamente el bug
 * que dejaría círculos puestos para siempre.
 */

const COMPANY = "11111111-1111-4111-8111-111111111111";
const TRIP = "44444444-4444-4444-8444-444444444444";
const LOCATION = "55555555-5555-4555-8555-555555555555";

let fetchMock: ReturnType<typeof vi.fn>;

function sent() {
  const [url, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit];
  return { url: new URL(url), init };
}

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

describe("recalcular la ETA", () => {
  it("es un POST a la ruta del viaje, con el ámbito de empresa", async () => {
    await recomputeTripEta(COMPANY, TRIP);

    const { url, init } = sent();
    expect(init.method).toBe("POST");
    expect(url.pathname).toBe(`/api/v1/planning/trips/${TRIP}/eta`);
    expect((init.headers as Record<string, string>)[COMPANY_ID_HEADER]).toBe(COMPANY);
  });
});

describe("el geocerco", () => {
  it("se fija con PUT sobre la ubicación", async () => {
    await setLocationGeofence(COMPANY, LOCATION, 250);

    const { url, init } = sent();
    expect(init.method).toBe("PUT");
    expect(url.pathname).toBe(`/api/v1/masterdata/locations/${LOCATION}/geofence`);
    expect(JSON.parse(init.body as string)).toEqual({ radiusMetres: 250 });
  });

  it("un radio nulo viaja en el cuerpo, porque null significa borrar y no 'no lo mandé'", async () => {
    await setLocationGeofence(COMPANY, LOCATION, null);

    const { url, init } = sent();
    expect(url.search).toBe("");
    expect(JSON.parse(init.body as string)).toEqual({ radiusMetres: null });
  });
});
