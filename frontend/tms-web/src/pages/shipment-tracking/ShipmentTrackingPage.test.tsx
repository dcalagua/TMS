import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { DocumentDeliveryRow } from "../../shared/api/documentDeliveriesApi";
import type { ShipmentTrackingRow, ShipmentTrackingSummary } from "../../shared/api/shipmentTrackingApi";

/**
 * Seguimiento de despachos (ADR-019 §2), con la API simulada en `fetch` y sin clave de Google
 * Maps: las cifras del resumen, las filas, que los filtros lleguen como parámetros, el panel de un
 * despacho con sus documentos por parada, y la alternativa accesible cuando no hay mapa.
 */

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "DEMO-LIMA", name: "Demo Lima" },
    hasCapability: () => true,
    hasPermission: () => true,
  }),
}));

// Sin clave: los mapas nunca piden el script y pintan su alternativa.
vi.mock("../../shared/maps/googleMapsLoader", () => ({
  isGoogleMapsConfigured: () => false,
  loadCoreLibrary: () => Promise.reject(new Error("no key")),
  loadMapsLibrary: () => Promise.reject(new Error("no key")),
  loadMarkerLibrary: () => Promise.reject(new Error("no key")),
  loadGeocodingLibrary: () => Promise.reject(new Error("no key")),
}));

import { ShipmentTrackingPage } from "./ShipmentTrackingPage";

const SUMMARY: ShipmentTrackingSummary = {
  shipments: 12, inTransit: 7, completed: 5, withPendingDocuments: 4, documentsTotal: 60, documentsPending: 18,
  documentsDelivered: 37, documentsRejected: 3, documentsNotDelivered: 2, awaitingReturn: 25, dispatchMismatches: 1,
};

const ROW: ShipmentTrackingRow = {
  tripId: "trip-1", shipmentNumber: "SH-00000042", tripStatus: "IN_TRANSIT", planningDate: "2026-10-12",
  carrierId: "car-1", carrierName: "Transportes Andinos SAC", dispatchReference: "DSP-7781",
  dispatchedAt: "2026-10-12T12:30:00Z", dispatchVerification: "MISMATCH", vehicleLicensePlate: "XYZ-987",
  driverName: "Juan Quispe", originName: "CD Lurín", originLatitude: -12.27, originLongitude: -76.87,
  stopsTotal: 2, stopsResolved: 1, documentCount: 3, pendingCount: 1, deliveredCount: 1, rejectedCount: 1,
  notDeliveredCount: 0, returnedCount: 0, inconsistentCount: 0, lastReportAt: "2026-10-12T15:00:00Z",
  stops: [
    { stopId: "stop-1", sequence: 1, destinationName: "Bodega San Martín", address: "Av. Perú 123",
      latitude: -12.05, longitude: -77.04, executionStatus: "COMPLETED", documentCount: 2, pendingCount: 0 },
    { stopId: "stop-2", sequence: 2, destinationName: "Minimarket Lucía", address: null,
      latitude: null, longitude: null, executionStatus: "PENDING", documentCount: 1, pendingCount: 1 },
  ],
};

const OTHER_ROW: ShipmentTrackingRow = {
  ...ROW, tripId: "trip-2", shipmentNumber: "SH-00000099", dispatchReference: "DSP-7790", dispatchVerification: "MATCHED",
  carrierName: "Rutas del Sur EIRL", vehicleLicensePlate: "DEF-456", driverName: "Ana Pérez", rejectedCount: 0, deliveredCount: 2, stops: [],
};

function doc(id: string, number: string, status: DocumentDeliveryRow["status"], orderNumbers: string[], tripId = "trip-1"): DocumentDeliveryRow {
  return {
    id, tripId, shipmentNumber: "SH-00000042", tripStatus: "IN_TRANSIT", dispatchedAt: "2026-10-12T12:30:00Z",
    carrierId: "car-1", carrierName: "Transportes Andinos SAC", vehicleLicensePlate: "XYZ-987", driverName: "Juan Quispe",
    documentId: `doc-${id}`, documentType: "INVOICE", documentNumber: number, orderNumbers, customerName: "Bodega San Martín",
    status, reasonCode: status === "REJECTED" ? "CUSTOMER_REFUSED" : null, remark: null,
    reportedAt: status === "PENDING" ? null : "2026-10-12T15:00:00Z", recordedAt: null,
    recordedByName: status === "PENDING" ? null : "Juan Quispe", returnedAt: null, returnedByName: null, evidenceCount: 0, inconsistency: null,
  };
}

const DOCUMENTS = [
  doc("dd-1", "F001-000123", "DELIVERED", ["TO-1"]),
  doc("dd-2", "F001-000124", "REJECTED", ["TO-1"]),
  doc("dd-3", "EG01-000777", "PENDING", ["TO-2"]),
  // Otro envío cuyo número contiene el buscado: no es de este viaje.
  doc("dd-9", "F001-999999", "PENDING", ["TO-1"], "trip-other"),
];

const TRIP_DETAIL = {
  trip: { id: "trip-1" },
  assignments: [
    { assignmentId: "a-1", orderId: "o-1", orderNumber: "TO-1", destinationId: "dest-1" },
    { assignmentId: "a-2", orderId: "o-2", orderNumber: "TO-2", destinationId: "dest-2" },
  ],
  stops: [{ id: "stop-1", destinationId: "dest-1" }, { id: "stop-2", destinationId: "dest-2" }],
  deliveries: [],
};

const NO_POSITIONS = {
  shipmentNumber: "SH-00000042", status: "IN_TRANSIT", trackable: true, providerConfigured: true,
  vehicleCode: null, vehicleLicensePlate: "XYZ-987", lastPosition: null, track: [],
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

const calls = (suffix: string) => fetchMock.mock.calls
  .map(([input]) => new URL(input as string))
  .filter((url) => url.pathname.endsWith(suffix));

beforeEach(() => {
  fetchMock = vi.fn(async (input: string) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/monitoring/shipment-tracking/summary")) return json(SUMMARY);
    if (url.pathname.endsWith("/monitoring/shipment-tracking")) {
      return json({ content: [ROW, OTHER_ROW], page: 0, size: 50, totalElements: 2 });
    }
    if (url.pathname.endsWith("/document-deliveries")) return json({ content: DOCUMENTS, page: 0, size: 200, totalElements: DOCUMENTS.length });
    if (url.pathname.endsWith("/planning/trips/trip-1")) return json(TRIP_DETAIL);
    if (url.pathname.endsWith("/tracking/trips/trip-1")) return json(NO_POSITIONS);
    if (url.pathname.endsWith("/fleet/carriers")) return json({ content: [], page: 0, size: 200, totalElements: 0 });
    return new Response("{}", { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ShipmentTrackingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** La tarjeta de un KPI por su título (el mismo texto puede ser también un estado en la tabla). */
const kpi = (title: string) => screen.getAllByText(title)
  .map((el) => el.closest(".MuiCard-root"))
  .find((card): card is HTMLElement => card instanceof HTMLElement) as HTMLElement;

describe("el seguimiento de despachos", () => {
  it("pinta las cifras del resumen y los despachos con lo que reportó el almacén", async () => {
    renderPage();

    expect(await screen.findByText("SH-00000042")).toBeInTheDocument();
    await waitFor(() => expect(within(kpi("Despachos")).getByText("12")).toBeInTheDocument());
    expect(within(kpi("En ruta")).getByText("7")).toBeInTheDocument();
    expect(within(kpi("Con documentos pendientes")).getByText("4")).toBeInTheDocument();
    expect(within(kpi("Documentos pendientes")).getByText("18")).toBeInTheDocument();
    expect(within(kpi("Cargo por recibir")).getByText("25")).toBeInTheDocument();
    expect(within(kpi("Diferencias de despacho")).getByText("1")).toBeInTheDocument();

    expect(screen.getByText("DSP-7781")).toBeInTheDocument();
    expect(screen.getByText("XYZ-987 · Juan Quispe")).toBeInTheDocument();
    expect(screen.getByText("Con diferencias")).toBeInTheDocument();
    expect(screen.getByText("Coincide con el plan")).toBeInTheDocument();
    expect(screen.getByText("1 rechazados")).toBeInTheDocument();
  });

  it("pide hoy a hoy por fecha de despacho y lleva los filtros como parámetros", async () => {
    renderPage();
    await screen.findByText("SH-00000042");

    const first = calls("/monitoring/shipment-tracking")[0];
    expect(first.searchParams.get("dateFrom")).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(first.searchParams.get("dateTo")).toBe(first.searchParams.get("dateFrom"));
    expect(first.searchParams.get("page")).toBe("0");
    expect(first.searchParams.get("size")).toBe("50");
    expect(first.searchParams.has("status")).toBe(false);
    expect(first.searchParams.has("documents")).toBe(false);
    const summary = calls("/monitoring/shipment-tracking/summary")[0];
    expect(summary.searchParams.get("dateFrom")).toBe(first.searchParams.get("dateFrom"));

    await userEvent.click(kpi("Con documentos pendientes"));
    await waitFor(() => expect(calls("/monitoring/shipment-tracking").some((u) => u.searchParams.get("documents") === "PENDING")).toBe(true));

    await userEvent.click(kpi("En ruta"));
    await waitFor(() => expect(calls("/monitoring/shipment-tracking").some((u) =>
      u.searchParams.get("status") === "IN_TRANSIT" && u.searchParams.get("documents") === "PENDING")).toBe(true));

    await userEvent.type(screen.getByRole("textbox", { name: "Envío, despacho, placa o conductor" }), "DSP-77");
    await waitFor(() => expect(calls("/monitoring/shipment-tracking").some((u) => u.searchParams.get("q") === "DSP-77")).toBe(true));
  });

  it("al abrir un despacho trae sus documentos, los pone en su parada y dice que no hay posiciones", async () => {
    renderPage();

    await userEvent.click(await screen.findByText("SH-00000042"));
    const drawer = await screen.findByRole("dialog");

    expect(await within(drawer).findByText("F001-000123")).toBeInTheDocument();
    const docsCall = calls("/document-deliveries")[0];
    expect(docsCall.searchParams.get("q")).toBe("SH-00000042");
    expect(docsCall.searchParams.get("size")).toBe("200");
    expect(docsCall.searchParams.get("dateFrom")).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    // Los documentos de TO-1 en la parada 1, el de TO-2 en la 2; el de otro viaje, en ninguna.
    const stops = within(drawer).getByRole("region", { name: "Paradas y documentos" });
    const stop1 = within(stops).getByRole("group", { name: "Parada 1" });
    expect(within(stop1).getByText("F001-000124")).toBeInTheDocument();
    expect(within(stop1).getByText("Cliente rechazó")).toBeInTheDocument();
    expect(within(within(stops).getByRole("group", { name: "Parada 2" })).getByText("EG01-000777")).toBeInTheDocument();
    expect(within(drawer).queryByText("F001-999999")).not.toBeInTheDocument();

    expect(await within(drawer).findByText("Sin posiciones GPS")).toBeInTheDocument();
    expect(within(drawer).getByText(/El mapa no está disponible/)).toBeInTheDocument();
  });

  it("al pulsar un documento abre su detalle", async () => {
    renderPage();
    await userEvent.click(await screen.findByText("SH-00000042"));
    const drawer = await screen.findByRole("dialog");
    await userEvent.click(await within(drawer).findByRole("button", { name: "Abrir el documento F001-000123" }));

    await waitFor(() => expect(calls("/document-deliveries/dd-1").length).toBeGreaterThan(0));
  });

  it("sin Google Maps, el mapa es una lista accesible que también selecciona el despacho", async () => {
    renderPage();
    await screen.findByText("SH-00000042");

    await userEvent.click(screen.getByRole("button", { name: "Mapa" }));
    expect(screen.getByRole("status")).toHaveTextContent("El mapa no está disponible en esta instalación");
    const list = screen.getByRole("list", { name: "Paradas de los despachos" });
    expect(within(list).getByText(/Bodega San Martín · Con rechazo o no entrega/)).toBeInTheDocument();

    await userEvent.click(within(list).getByRole("button", { name: "Seleccionar el despacho SH-00000042" }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    await waitFor(() => expect(calls("/document-deliveries").length).toBeGreaterThan(0));
  });
});
