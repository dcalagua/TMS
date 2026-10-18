import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { CostToServeView } from "../../shared/api/costToServeApi";

/**
 * Costo para servir (fase 70), con la API simulada en `fetch`.
 *
 * Lo que se protege: la pantalla dice que no hay margen, agrupa por lo que se elige y lo pide así
 * al servidor, cada moneda es su fila, lo que no tiene base se ve contado y no como cero de costo,
 * un costo unitario sin divisor es una raya, y exportar pide el CSV del mismo filtro.
 */

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "DEMO-LIMA", name: "Demo Lima" },
    hasCapability: () => true,
    hasPermission: () => true,
  }),
}));

const saveDownloadedFile = vi.fn();
vi.mock("../../shared/api/httpClient", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../shared/api/httpClient")>()),
  saveDownloadedFile: (...args: unknown[]) => saveDownloadedFile(...args),
}));

import { CostToServePage } from "./CostToServePage";

const REPORT: CostToServeView = {
  from: "2026-10-01", to: "2026-10-18", groupBy: "CUSTOMER", basis: "WEIGHT", marginAvailable: false,
  marginNote: "No margin: TMS has no sales tariff, so it reports the cost to serve and not what was earned.",
  totals: [{
    currency: "PEN", trips: 3, cost: 2400, allocated: 1800, tripsNotAllocated: 1, unallocatedOrders: 2,
    invoicedTrips: 1, actualTrips: 1, estimatedTrips: 1, costPerKg: 1.5,
  }],
  groups: [
    {
      key: "CUST-1", label: "CUST-1", currency: "PEN", trips: 2, orders: 3, unallocatedOrders: 1, cost: 1200,
      weightKg: 800, volumeM3: 4, costPerKg: 1.5, costPerM3: 300, costPerOrder: 400,
    },
    {
      key: null, label: null, currency: "USD", trips: 1, orders: 1, unallocatedOrders: 0, cost: 600,
      weightKg: 0, volumeM3: 0, costPerKg: null, costPerM3: null, costPerOrder: 600,
    },
  ],
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  saveDownloadedFile.mockClear();
  fetchMock = vi.fn(async (input: string) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/settlement/cost-to-serve/export")) {
      return new Response("group_by,key\n", { status: 200, headers: { "content-type": "text/csv" } });
    }
    if (url.pathname.endsWith("/settlement/cost-to-serve")) {
      return json({ ...REPORT, groupBy: url.searchParams.get("groupBy") ?? "CUSTOMER" });
    }
    return new Response("{}", { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <CostToServePage />
    </QueryClientProvider>,
  );
}

function reportCalls() {
  return fetchMock.mock.calls
    .map(([input]) => new URL(input as string))
    .filter((url) => url.pathname.endsWith("/settlement/cost-to-serve"));
}

describe("CostToServePage", () => {
  it("dice que no hay margen y pinta un grupo por fila y moneda", async () => {
    renderPage();

    expect(screen.getByText(/Sin margen: TMS no tiene tarifa de venta/)).toBeInTheDocument();
    const row = (await screen.findByText("CUST-1")).closest("tr") as HTMLElement;
    expect(within(row).getByText("PEN")).toBeInTheDocument();
    // Lo que no tiene base se cuenta.
    expect(within(row).getByText("1")).toBeInTheDocument();
    // Sin grupo y sin peso: el nombre dice «sin cliente» y el costo por kg es una raya, nunca un cero.
    const usd = screen.getByText("(sin cliente)").closest("tr") as HTMLElement;
    expect(within(usd).getAllByText("—").length).toBe(2);
    expect(screen.getByText("Base de reparto: Por peso (kg)")).toBeInTheDocument();
    expect(screen.getByText("Pedidos sin base")).toBeInTheDocument();
  });

  it("pide al servidor la agrupación elegida", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("CUST-1");

    await user.click(screen.getByRole("button", { name: "Destino" }));

    await screen.findByText("Destino", { selector: "th *, th" });
    expect(reportCalls().some((url) => url.searchParams.get("groupBy") === "DESTINATION")).toBe(true);
    expect(reportCalls().every((url) => url.searchParams.get("from") !== null && url.searchParams.get("to") !== null))
      .toBe(true);
  });

  it("exporta el CSV del mismo filtro", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("CUST-1");

    await user.click(screen.getByRole("button", { name: "Exportar CSV" }));

    const exportCall = fetchMock.mock.calls.map(([input]) => new URL(input as string))
      .find((url) => url.pathname.endsWith("/cost-to-serve/export"));
    expect(exportCall?.searchParams.get("format")).toBe("CSV");
    expect(exportCall?.searchParams.get("groupBy")).toBe("CUSTOMER");
    expect(saveDownloadedFile).toHaveBeenCalledTimes(1);
  });
});
