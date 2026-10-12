import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type {
  DocumentDeliveryDetail, DocumentDeliveryRow, DocumentDeliverySummary,
} from "../../shared/api/documentDeliveriesApi";

/**
 * El monitor de entrega de documentos (ADR-018), con la API simulada en `fetch`.
 *
 * Se protege lo que la oficina lee primero: las cifras del resumen, el desglose por transportista,
 * las filas con su estado y el aviso de inconsistencia, y que el detalle se abra al pulsar una fila.
 */

let capabilities: string[] = ["DOCUMENT_DELIVERY_VIEW", "DOCUMENT_DELIVERY_MANAGE"];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "DEMO-LIMA", name: "Demo Lima" },
    hasCapability: (c: string) => capabilities.includes(c),
    hasPermission: () => true,
  }),
}));

import { DocumentDeliveriesPage } from "./DocumentDeliveriesPage";

const SUMMARY: DocumentDeliverySummary = {
  total: 40, pending: 11, delivered: 22, rejected: 4, notDelivered: 3, returned: 9, awaitingReturn: 20, inconsistent: 2,
  byCarrier: [
    { carrierId: "car-1", carrierName: "Transportes Andinos SAC", total: 30, pending: 8, delivered: 17, rejected: 3, notDelivered: 2, awaitingReturn: 15 },
    { carrierId: "car-2", carrierName: "Rutas del Sur EIRL", total: 10, pending: 3, delivered: 5, rejected: 1, notDelivered: 1, awaitingReturn: 5 },
  ],
};

const ROW: DocumentDeliveryRow = {
  id: "dd-1", tripId: "trip-1", shipmentNumber: "SH-00000042", tripStatus: "IN_TRANSIT",
  dispatchedAt: "2026-10-12T12:30:00Z", carrierId: "car-1", carrierName: "Transportes Andinos SAC",
  vehicleLicensePlate: "ABC-123", driverName: "Juan Quispe", documentId: "doc-1", documentType: "INVOICE",
  documentNumber: "F001-000123", orderNumbers: ["TO-00000001"], customerName: "Bodega San Martín",
  status: "DELIVERED", reasonCode: null, remark: null, reportedAt: "2026-10-12T15:00:00Z",
  recordedAt: "2026-10-12T15:00:05Z", recordedByName: "Juan Quispe", returnedAt: null, returnedByName: null,
  evidenceCount: 2, inconsistency: "DOCUMENT_DELIVERED_GOODS_FAILED",
};

const REJECTED_ROW: DocumentDeliveryRow = {
  ...ROW, id: "dd-2", documentId: "doc-2", documentType: "GRE_REMITENTE", documentNumber: "EG01-000777",
  status: "REJECTED", reasonCode: "CUSTOMER_REFUSED", evidenceCount: 0, inconsistency: null,
};

const DETAIL: DocumentDeliveryDetail = {
  delivery: ROW,
  events: [{
    id: "ev-1", eventType: "REPORTED", status: "DELIVERED", reasonCode: null, remark: "Recibió el encargado",
    reportedAt: "2026-10-12T15:00:00Z", actorName: "Juan Quispe", recordedAt: "2026-10-12T15:00:05Z",
  }],
  evidence: [],
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  capabilities = ["DOCUMENT_DELIVERY_VIEW", "DOCUMENT_DELIVERY_MANAGE"];
  fetchMock = vi.fn(async (input: string) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/document-deliveries/summary")) return json(SUMMARY);
    if (url.pathname.endsWith("/document-deliveries/dd-1")) return json(DETAIL);
    if (url.pathname.endsWith("/document-deliveries")) {
      return json({ content: [ROW, REJECTED_ROW], page: 0, size: 25, totalElements: 2 });
    }
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
        <DocumentDeliveriesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("el monitor de entrega de documentos", () => {
  it("pinta las cifras del resumen y el desglose por transportista", async () => {
    renderPage();

    expect(await screen.findByText("Rutas del Sur EIRL")).toBeInTheDocument();
    const awaiting = screen.getAllByText("Cargo por recibir")[0].closest(".MuiCard-root") as HTMLElement;
    expect(within(awaiting).getByText("20")).toBeInTheDocument();
    const inconsistent = screen.getByText("Inconsistencias").closest(".MuiCard-root") as HTMLElement;
    expect(within(inconsistent).getByText("2")).toBeInTheDocument();
  });

  it("pide la última semana por fecha de despacho y pinta las filas con estado e inconsistencia", async () => {
    renderPage();

    expect(await screen.findByText("F001-000123")).toBeInTheDocument();
    expect(screen.getByText("EG01-000777")).toBeInTheDocument();
    expect(screen.getByText("Cliente rechazó")).toBeInTheDocument();
    expect(screen.getByText("Inconsistencia")).toBeInTheDocument();
    expect(screen.getAllByText("Por recibir").length).toBeGreaterThan(0);

    const listCall = fetchMock.mock.calls
      .map(([input]) => new URL(input as string))
      .find((url) => url.pathname.endsWith("/document-deliveries"));
    expect(listCall?.searchParams.get("dateFrom")).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(listCall?.searchParams.get("dateTo")).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(listCall?.searchParams.get("page")).toBe("0");
    expect(listCall?.searchParams.get("size")).toBe("25");
  });

  it("al pulsar una fila abre el detalle con el historial, la inconsistencia explicada y la acción de cargo", async () => {
    renderPage();

    await userEvent.click(await screen.findByText("F001-000123"));

    expect(await screen.findByText("Recibió el encargado")).toBeInTheDocument();
    expect(screen.getByText(/Uno de los dos registros no es correcto/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirmar cargo recibido" })).toBeInTheDocument();
  });

  it("sin DOCUMENT_DELIVERY_MANAGE no ofrece confirmar el cargo", async () => {
    capabilities = ["DOCUMENT_DELIVERY_VIEW"];
    renderPage();

    await userEvent.click(await screen.findByText("F001-000123"));
    await screen.findByText("Recibió el encargado");

    await waitFor(() => expect(screen.queryByRole("button", { name: "Confirmar cargo recibido" })).not.toBeInTheDocument());
  });
});
