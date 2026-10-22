import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Ítem 11: el seguimiento de reparto se comparte por chat, y el enlace tiene que abrir el mismo
 * día y el mismo estado. `/delivery-tracking?date=2026-10-20&status=COMPLETED` es el criterio de
 * aceptación literal.
 */

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "DEMO", name: "Demo" },
    hasCapability: () => true,
    hasPermission: () => true,
  }),
}));

import { DeliveryTrackingPage } from "./DeliveryTrackingPage";

let fetchMock: ReturnType<typeof vi.fn>;

const tripCalls = () => fetchMock.mock.calls
  .map(([input]) => new URL(input as string))
  .filter((url) => url.pathname.endsWith("/monitoring/control-tower/trips"));

beforeEach(() => {
  fetchMock = vi.fn(async () => new Response(JSON.stringify({ content: [], page: 0, size: 200, totalElements: 0 }), {
    status: 200, headers: { "content-type": "application/json" },
  }));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderAt(url: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <MemoryRouter initialEntries={[url]}>
        <DeliveryTrackingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("el seguimiento de reparto con los filtros en la URL", () => {
  it("abre con la fecha y el estado del enlace", async () => {
    renderAt("/delivery-tracking?date=2026-10-20&status=COMPLETED");

    await waitFor(() => expect(tripCalls().length).toBeGreaterThan(0));
    const first = tripCalls()[0];
    expect(first.searchParams.get("date")).toBe("2026-10-20");
    expect(first.searchParams.get("status")).toBe("COMPLETED");
  });

  it("sin parámetros pide hoy y lo que está en ruta, como siempre", async () => {
    renderAt("/delivery-tracking");

    await waitFor(() => expect(tripCalls().length).toBeGreaterThan(0));
    const first = tripCalls()[0];
    expect(first.searchParams.get("date")).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(first.searchParams.get("status")).toBe("IN_TRANSIT");
  });

  it("?status= vacío es «todos»: no manda estado", async () => {
    renderAt("/delivery-tracking?date=2026-10-20&status=");

    await waitFor(() => expect(tripCalls().length).toBeGreaterThan(0));
    expect(tripCalls()[0].searchParams.has("status")).toBe(false);
  });
});
