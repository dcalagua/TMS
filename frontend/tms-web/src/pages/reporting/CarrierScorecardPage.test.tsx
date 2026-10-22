import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type {
  CarrierScorecardRow, CarrierScorecardTripsView, CarrierScorecardView,
} from "../../shared/api/carrierScorecardApi";

/**
 * Scorecard de transportistas (ITEM 3). Lo que se protege:
 *
 * - un `null` del backend se pinta «sin datos» y nunca 0%;
 * - un bloque sin permiso (ofertas, costo…) quita su columna en vez de llenarla de rayas;
 * - «Ver viajes» pide el detalle de ese transportista, y `?carrier=` lo abre directo.
 */

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({ selected: { id: "company-1", code: "DEMO-LIMA", name: "Demo Lima" } }),
}));

import { CarrierScorecardPage } from "./CarrierScorecardPage";

const TRLIT = "11111111-1111-4111-8111-111111111111";

function row(overrides: Partial<CarrierScorecardRow>): CarrierScorecardRow {
  return {
    carrierKey: TRLIT, carrierId: TRLIT, ownFleet: false, carrierCode: "TRLIT", carrierName: "Transportes Litoral",
    trips: 2, tripsCancelled: 0, tripsExecuted: 2, tripsCompleted: 1,
    departuresMeasured: 2, departuresLate: 1, onTimeDeparturePercent: 50,
    stops: 2, serviceWindowsMeasured: 2, serviceWindowsMissed: 0, inWindowPercent: 100,
    deliveriesRecorded: 2, deliveriesDelivered: 1, deliveriesShort: 1, deliveriesNotAttempted: 0,
    deliverySuccessPercent: 50, exceptions: 1, exceptionsOpen: 0, exceptionsPer100Trips: 50,
    tenders: { offered: 1, accepted: 1, rejected: 0, expired: 0, awaiting: 0, timed: 1, acceptancePercent: 100, averageResponseMinutes: 30 },
    documents: null, invoices: null, cost: null,
    ...overrides,
  };
}

function scorecard(overrides: Partial<CarrierScorecardView> = {}): CarrierScorecardView {
  return {
    from: "2026-10-10", to: "2026-10-20", days: 11, generatedAt: "2026-10-21T12:00:00Z",
    tendersVisible: true, documentsVisible: false, invoicesVisible: false, costVisible: false,
    unattributedTrips: 1,
    carriers: [
      row({}),
      row({
        carrierKey: "OWN_FLEET", carrierId: null, ownFleet: true, carrierCode: null, carrierName: null,
        trips: 1, tripsExecuted: 0, departuresMeasured: 0, departuresLate: 0, onTimeDeparturePercent: null,
        serviceWindowsMeasured: 0, inWindowPercent: null, deliveriesRecorded: 0, deliverySuccessPercent: null,
        exceptions: 0, exceptionsPer100Trips: 0,
        tenders: { offered: 0, accepted: 0, rejected: 0, expired: 0, awaiting: 0, timed: 0, acceptancePercent: null, averageResponseMinutes: null },
      }),
    ],
    ...overrides,
  };
}

const TRIPS: CarrierScorecardTripsView = {
  carrierKey: TRLIT, carrierId: TRLIT, ownFleet: false, carrierName: "Transportes Litoral",
  from: "2026-10-10", to: "2026-10-20", total: 1, truncated: false,
  trips: [{
    tripId: "22222222-2222-4222-8222-222222222222", shipmentNumber: "SH-00000042", planningDate: "2026-10-15",
    status: "IN_TRANSIT", plannedDepartureAt: "2026-10-15T13:00:00Z", actualDepartureAt: "2026-10-15T14:00:00Z",
    departureLate: true, stops: 2, windowsMeasured: 2, windowsMissed: 1, deliveriesRecorded: 2,
    deliveriesDelivered: 1, deliveriesShort: 1, exceptions: 1,
  }],
};

function json(body: unknown) {
  return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
}

function renderPage(path = "/reporting/carriers") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <CarrierScorecardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/reporting/carrier-scorecard/trips")) return json(TRIPS);
    return json(scorecard());
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("CarrierScorecardPage", () => {
  it("anuncia que está cargando mientras la petición está en vuelo", () => {
    fetchMock.mockImplementation(() => new Promise(() => {}));
    renderPage();
    expect(screen.getByText(/cargando/i)).toBeInTheDocument();
  });

  it("pinta cada porcentaje con su denominador, y «sin datos» —nunca 0%— cuando no hubo qué medir", async () => {
    renderPage();

    expect(await screen.findByText("Scorecard de transportistas")).toBeInTheDocument();
    const trlit = screen.getByText("Transportes Litoral").closest("tr") as HTMLElement;
    expect(within(trlit).getAllByText(/^50\s?%$/).length).toBeGreaterThan(0);
    expect(within(trlit).getAllByText("de 2").length).toBeGreaterThan(0);

    const own = screen.getByText("Flota propia").closest("tr") as HTMLElement;
    // Salida, ventana, entregas, aceptación y respuesta: sin denominador en los cinco.
    expect(within(own).getAllByText("sin datos")).toHaveLength(5);
    expect(within(own).queryByText(/^0\s?%$/)).not.toBeInTheDocument();
    // Un viaje que nadie atribuyó se cuenta aparte.
    expect(screen.getByText("Viajes sin transportista")).toBeInTheDocument();
  });

  it("quita las columnas de un bloque que la cuenta no puede ver", async () => {
    fetchMock.mockImplementation(async () => json(scorecard({
      tendersVisible: false,
      carriers: [row({ tenders: null })],
    })));
    renderPage();

    await screen.findByText("Transportes Litoral");
    expect(screen.queryByText("Ofertas aceptadas")).not.toBeInTheDocument();
    expect(screen.queryByText("Costo por kg")).not.toBeInTheDocument();
    expect(screen.getAllByText("Salida a tiempo").length).toBeGreaterThan(0);
  });

  it("«Ver viajes» pide el detalle de ese transportista y lista sus viajes", async () => {
    renderPage();

    const trlit = (await screen.findByText("Transportes Litoral")).closest("tr") as HTMLElement;
    fireEvent.click(within(trlit).getByRole("button", { name: /ver viajes/i }));

    expect(await screen.findByText("SH-00000042")).toBeInTheDocument();
    const call = fetchMock.mock.calls.map(([input]) => String(input))
      .find((url) => url.includes("/reporting/carrier-scorecard/trips"));
    expect(call).toContain(`carrier=${TRLIT}`);
    expect(screen.getByText("Tarde")).toBeInTheDocument();
  });

  it("abre el detalle directo con ?carrier=, que es lo que usa «Ver desempeño»", async () => {
    renderPage(`/reporting/carriers?carrier=${TRLIT}`);
    expect(await screen.findByText("SH-00000042")).toBeInTheDocument();
  });
});
