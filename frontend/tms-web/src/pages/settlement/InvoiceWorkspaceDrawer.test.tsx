import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { CarrierInvoiceView } from "../../shared/api/settlementApi";
import { InvoiceWorkspaceDrawer } from "./InvoiceWorkspaceDrawer";

/**
 * La factura abierta (fase 50): contra qué se comparó, de qué línea habla cada diferencia, que una
 * penalidad queda fuera de la comparación y que la exportación manda el formato elegido.
 */

const INVOICE: CarrierInvoiceView = {
  id: "inv-1", carrierId: "car-1", carrierName: "Transportes Andinos SAC", invoiceNumber: "F001-77",
  invoiceDate: "2026-10-01", currency: "PEN", totalAmount: 1330, status: "APPROVED",
  allowedTransitions: ["EXPORTED"], externalReference: null, receivedAt: "2026-10-02T10:00:00Z",
  notes: null, version: 3,
  lines: [
    {
      id: "l-1", lineNumber: 1, tripId: "t-1", shipmentNumber: "SH-1", chargeCode: "FREIGHT",
      description: "Flete Lima - Ica", quantity: null, unitAmount: null, lineAmount: 1000,
      expectedAmount: 950, expectedBasis: "ESTIMATED", actualAmount: null, differenceAmount: 50,
    },
    {
      id: "l-2", lineNumber: 2, tripId: "t-1", shipmentNumber: "SH-1", chargeCode: "TOLL",
      description: "Peajes", quantity: null, unitAmount: null, lineAmount: 250,
      expectedAmount: 250, expectedBasis: "ESTIMATED", actualAmount: null, differenceAmount: 0,
    },
    {
      id: "l-3", lineNumber: 3, tripId: "t-1", shipmentNumber: "SH-1", chargeCode: "PENALTY",
      description: "Penalidad por demora", quantity: null, unitAmount: null, lineAmount: 80,
      expectedAmount: null, expectedBasis: null, actualAmount: null, differenceAmount: null,
    },
  ],
  match: {
    status: "DISCREPANCY", expectedAmount: 1200, expectedBasis: "ESTIMATED", actualAmount: null,
    invoicedAmount: 1330, differenceAmount: 130, toleranceAbsolute: null, tolerancePercentage: null,
    matchedTripCount: 1, unmatchedLineCount: 0, computedAt: "2026-10-02T10:05:00Z",
  },
  discrepancies: [{
    id: "d-1", type: "LINE_AMOUNT", invoiceLineId: "l-1", expectedAmount: 950, invoicedAmount: 1000,
    differenceAmount: 50, detail: "La línea factura 50,00 más de lo esperado.", status: "ACCEPTED",
    resolutionNotes: null, resolvedAt: "2026-10-02T11:00:00Z",
  }],
  approvals: [],
  export: null,
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/export") && init?.method === "POST") {
      return json({
        id: "x-1", exportReference: "AP-0001", format: url.searchParams.get("format"),
        payload: "a,b", exportedAt: "2026-10-03T09:00:00Z", alreadyExported: false,
      });
    }
    return json(INVOICE);
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderDrawer() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <InvoiceWorkspaceDrawer companyId="company-1" invoiceId="inv-1" onClose={() => {}} onChanged={() => {}} />
    </QueryClientProvider>,
  );
}

describe("la factura abierta", () => {
  it("dice contra qué se comparó, el cargo de cada línea y deja la penalidad fuera", async () => {
    renderDrawer();

    expect(await screen.findByText("Contra estimado")).toBeInTheDocument();
    expect(screen.getByText("Peaje")).toBeInTheDocument();
    expect(screen.getByText("Flete (todo incluido)")).toBeInTheDocument();
    const penaltyRow = screen.getByText("Penalidad por demora").closest("tr") as HTMLElement;
    expect(within(penaltyRow).getByText("Fuera de la comparación")).toBeInTheDocument();
  });

  it("nombra la línea de la que habla una diferencia", async () => {
    renderDrawer();

    expect(await screen.findByText("Línea 1")).toBeInTheDocument();
  });

  it("exporta en el formato elegido", async () => {
    const user = userEvent.setup();
    renderDrawer();
    await screen.findByText("Contra estimado");

    await user.click(screen.getByRole("button", { name: "CSV" }));
    await user.click(screen.getByRole("button", { name: /exportar a contabilidad/i }));

    const exportCall = fetchMock.mock.calls
      .map(([input, init]) => ({ url: new URL(input as string), init: init as RequestInit }))
      .find(({ url, init }) => url.pathname.endsWith("/export") && init.method === "POST");
    expect(exportCall?.url.searchParams.get("format")).toBe("CSV");
  });
});
