import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PayableSummaryView } from "../../shared/api/payablesApi";

/**
 * La tarjeta de facturas vencidas en la torre (fase 65): sólo existe para quien lee facturas, y
 * un fallo de su consulta no rompe la torre - la tarjeta simplemente no aparece.
 */

let capabilities: string[] = [];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1" },
    hasCapability: (c: string) => capabilities.includes(c),
  }),
}));

import { PayablesSummaryCard } from "./PayablesSummaryCard";

const SUMMARY: PayableSummaryView = {
  asOf: "2026-10-18", dueSoonDays: 7, pendingPaymentRequests: 2,
  currencies: [{ currency: "PEN", overdueCount: 3, overdueAmount: 4500, dueSoonCount: 1, dueSoonAmount: 800 }],
};

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn(async () => new Response(JSON.stringify(SUMMARY), {
    status: 200, headers: { "content-type": "application/json" },
  }));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderCard() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <PayablesSummaryCard />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("la tarjeta de facturas vencidas de la torre", () => {
  it("sin SETTLEMENT_VIEW no se pinta ni se pide", () => {
    capabilities = ["TRANSPORT_MONITOR_VIEW"];
    const { container } = renderCard();

    expect(container).toBeEmptyDOMElement();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("con SETTLEMENT_VIEW enseña lo vencido, lo que vence pronto y lo que espera confirmación", async () => {
    capabilities = ["TRANSPORT_MONITOR_VIEW", "SETTLEMENT_VIEW"];
    renderCard();

    expect(await screen.findByText("Facturas vencidas / por vencer")).toBeInTheDocument();
    expect(screen.getByText(/3 vencidas/)).toBeInTheDocument();
    expect(screen.getByText(/Vencen en 7 días/)).toBeInTheDocument();
    expect(screen.getByText("2 pagos por confirmar")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ver cuentas por pagar" })).toHaveAttribute("href", "/settlement/payables");
    expect(new URL(fetchMock.mock.calls[0][0] as string).pathname).toMatch(/\/settlement\/payables\/summary$/);
  });

  it("si la consulta falla, no pinta nada", async () => {
    capabilities = ["SETTLEMENT_VIEW"];
    fetchMock.mockImplementation(async () => new Response("{}", { status: 500 }));
    const { container } = renderCard();

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
