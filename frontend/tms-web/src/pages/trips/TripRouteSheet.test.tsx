import { render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { LogisticsDocumentView } from "../../shared/api/logisticsDocumentsApi";
import type { OrderDetailView } from "../../shared/api/ordersApi";
import type { TripDetailView } from "../../shared/api/planningApi";
import { setLang } from "../../lib/i18n";
import { TripRouteSheet, type RouteSheetData } from "./TripRouteSheet";
import { stopsWithOrders } from "./routeSheet";

/**
 * La hoja de ruta imprimible (ítem 8 de la evaluación): lo que el conductor lleva a la garita.
 *
 * Lo que se protege: las paradas salen en el orden de visita con su dirección, ventana y pedidos;
 * cada pedido con sus líneas y su guía de remisión SRI 06 (001-001-000000123, la del guion de
 * PLAPASA); una casilla «Recibí conforme» por parada; los totales del envío; ningún importe; y que
 * sin permiso de pedidos lo diga en vez de imprimir una hoja que parece completa.
 */

afterEach(() => setLang("es"));

const DETAIL = {
  trip: {
    id: "trip-1", shipmentNumber: "VJ-00000002", planNumber: "PL-0007", planningDate: "2026-10-20",
    status: "IN_TRANSIT", originCode: "GYE-CD", originName: "Planta Guayaquil",
    vehicleLicensePlate: "GSD-1357", vehicleCode: "CAM-04", vehicleTypeCode: "FURGON-5T-BAJO",
    carrierName: "Transportes del Litoral S.A. (demo)", acceptedCarrierName: null,
    driverName: "Demo Dos, Conductor", driverLicenseNumber: "0920000015", driverPhone: "+593 99 000 0002",
    routeCode: "RUTA-GYE-SUR", routeName: "Planta -> Centro y sur", plannedDepartureAt: "2026-10-20T13:30:00Z",
  },
  stops: [
    { id: "s-2", sequence: 2, destinationId: "d-agr", destinationCode: "CLI-AGR-01", destinationName: "Agroexportadora",
      address: "Km 3 vía a Montalvo", serviceWindowStart: "13:00:00", serviceWindowEnd: "17:00:00", etaArrivalAt: null },
    { id: "s-1", sequence: 1, destinationId: "d-fer", destinationCode: "CLI-FER-01", destinationName: "Ferretería El Constructor",
      address: "Av. Quito y Sucre", serviceWindowStart: "08:00:00", serviceWindowEnd: "12:00:00", etaArrivalAt: "2026-10-20T14:10:00Z" },
  ],
  assignments: [
    { assignmentId: "a-1", orderId: "o-fer", orderNumber: "OT-00000003", destinationId: "d-fer",
      customerName: "Ferretería El Constructor", requestedWindowStart: null, requestedWindowEnd: null,
      assignedWeightKg: 410.5, assignedVolumeM3: 6.2, assignedPallets: 4, wholeOrder: true },
    { assignmentId: "a-2", orderId: "o-agr", orderNumber: "OT-00000004", destinationId: "d-agr",
      customerName: "Agroexportadora", requestedWindowStart: null, requestedWindowEnd: null,
      assignedWeightKg: 100, assignedVolumeM3: 2, assignedPallets: 1, wholeOrder: false },
  ],
  exceptions: [], deliveries: [],
} as unknown as TripDetailView;

const ORDER = {
  id: "o-fer", orderNumber: "OT-00000003",
  lines: [
    { id: "l-1", lineNumber: 1, materialCode: "PLP-1222", materialDescription: "Silla plástica apilable", quantity: 30,
      uom: "UN", lineWeightKg: 75, lineVolumeM3: 1.9 },
  ],
} as unknown as OrderDetailView;

const GUIDE = {
  id: "doc-1", sourceSystem: "ERP", documentType: "REMISSION_GUIDE", documentNumber: "001-001-000000123",
  issueDate: "2026-10-20", recipientName: null, amount: 987.65, currency: "USD", externalStatus: null,
  receivedAt: "2026-10-20T12:00:00Z",
  fiscal: { fiscalCountry: "EC", fiscalDocumentCode: "06", authorizationNumber: "2010202606099999999900120010010000001231234567811",
    transportStartDate: null, transportEndDate: null, carrierTaxId: null, carrierName: null, vehiclePlate: null,
    originAddress: null, transferReason: null },
} as LogisticsDocumentView;

function data(overrides: Partial<RouteSheetData> = {}): RouteSheetData {
  return {
    companyName: "PLAPASA (demo)",
    detail: DETAIL,
    orders: new Map([["o-fer", ORDER]]),
    documents: new Map([["o-fer", [GUIDE]]]),
    linesAvailable: true,
    printedAt: new Date("2026-10-20T13:00:00Z"),
    ...overrides,
  };
}

describe("la hoja de ruta", () => {
  it("encabeza con la empresa, el envío, el plan, la placa, el transportista y el conductor", () => {
    render(<TripRouteSheet data={data()} />);

    const sheet = screen.getByRole("article", { name: "Hoja de ruta VJ-00000002" });
    expect(within(sheet).getByText("PLAPASA (demo)")).toBeInTheDocument();
    expect(within(sheet).getByText("VJ-00000002")).toBeInTheDocument();
    expect(within(sheet).getByText(/Plan PL-0007/)).toBeInTheDocument();
    expect(within(sheet).getByText("GSD-1357 · CAM-04 · FURGON-5T-BAJO")).toBeInTheDocument();
    expect(within(sheet).getByText("Transportes del Litoral S.A. (demo)")).toBeInTheDocument();
    expect(within(sheet).getByText(/Demo Dos, Conductor/)).toBeInTheDocument();
    expect(within(sheet).getByText(/Licencia 0920000015/)).toBeInTheDocument();
  });

  it("ordena las paradas por secuencia, cada una con su dirección, ventana y casilla de firma", () => {
    render(<TripRouteSheet data={data()} />);

    const stops = screen.getAllByRole("region", { name: /^Parada \d$/ });
    expect(stops.map((s) => s.getAttribute("aria-label"))).toEqual(["Parada 1", "Parada 2"]);
    expect(within(stops[0]).getByText(/CLI-FER-01/)).toBeInTheDocument();
    expect(within(stops[0]).getByText("Av. Quito y Sucre")).toBeInTheDocument();
    expect(within(stops[0]).getByText(/Ventana 08:00 – 12:00/)).toBeInTheDocument();
    for (const stop of stops) {
      expect(within(stop).getByText("Recibí conforme")).toBeInTheDocument();
      expect(within(stop).getByText("Firma y sello")).toBeInTheDocument();
    }
  });

  it("lleva las líneas del pedido y la guía de remisión SRI 06 con su clave de acceso", () => {
    render(<TripRouteSheet data={data()} />);

    const lines = screen.getByRole("table", { name: "Líneas del pedido OT-00000003" });
    expect(within(lines).getByText("PLP-1222")).toBeInTheDocument();
    expect(within(lines).getByText("Silla plástica apilable")).toBeInTheDocument();
    expect(within(lines).getByText("30 UN")).toBeInTheDocument();
    const documents = screen.getByRole("list", { name: "Documentos del pedido OT-00000003" });
    expect(within(documents).getByText("001-001-000000123")).toBeInTheDocument();
    expect(within(documents).getByText(/SRI 06/)).toBeInTheDocument();
    expect(within(documents).getByText(/Clave de acceso/)).toBeInTheDocument();
  });

  it("suma los totales de lo asignado y no imprime importes", () => {
    render(<TripRouteSheet data={data()} />);

    const totals = screen.getByRole("table", { name: "Totales del envío" });
    expect(within(totals).getAllByRole("cell").map((c) => c.textContent)).toEqual(["2", "2", "510,5 kg", "8,2 m³", "5"]);
    expect(screen.queryByText(/987/)).not.toBeInTheDocument();
    expect(screen.queryByText(/USD/)).not.toBeInTheDocument();
  });

  it("dice cuando un pedido viaja en parte", () => {
    render(<TripRouteSheet data={data()} />);

    const second = screen.getByRole("region", { name: "Parada 2" });
    expect(within(second).getByText(/Viaja una parte del pedido/)).toBeInTheDocument();
  });

  it("sin permiso de pedidos lo dice, en vez de imprimir una hoja que parece completa", () => {
    render(<TripRouteSheet data={data({ orders: new Map(), documents: new Map(), linesAvailable: false })} />);

    expect(screen.getByText(/Tu perfil no puede leer pedidos/)).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: /Líneas del pedido/ })).not.toBeInTheDocument();
    expect(screen.getByText(/Pedido OT-00000003/)).toBeInTheDocument();
  });

  it("se imprime en inglés", () => {
    setLang("en");
    render(<TripRouteSheet data={data()} />);

    expect(screen.getByRole("article", { name: "Route sheet VJ-00000002" })).toBeInTheDocument();
    expect(screen.getAllByText("Received in good order")).toHaveLength(2);
    expect(screen.getByText("Driver's signature")).toBeInTheDocument();
  });
});

describe("paradas y pedidos", () => {
  it("no deja fuera un pedido que no tiene parada", () => {
    const detail = {
      ...DETAIL,
      assignments: [...DETAIL.assignments, { ...DETAIL.assignments[0], assignmentId: "a-3", destinationId: "d-otro" }],
    } as TripDetailView;
    const groups = stopsWithOrders(detail);
    expect(groups.map((g) => g.stop?.sequence ?? null)).toEqual([1, 2, null]);
    expect(groups[2].orders.map((o) => o.assignmentId)).toEqual(["a-3"]);
  });
});
