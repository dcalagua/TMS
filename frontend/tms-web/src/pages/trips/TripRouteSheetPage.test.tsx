import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * `/trips/:tripId/print`: qué pide la página según el permiso y que «Imprimir» sea la impresión
 * del navegador. El contenido de la hoja lo cubre `TripRouteSheet.test.tsx`.
 */

let permissions: string[] = [];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "QAS-PLAPASA", name: "PLAPASA (demo)" },
    hasCapability: () => true,
    hasPermission: (p: string) => permissions.includes(p),
  }),
}));

import { TripRouteSheetPage } from "./TripRouteSheetPage";

const DETAIL = {
  trip: { id: "trip-1", shipmentNumber: "VJ-00000002", planningDate: "2026-10-20", status: "CONFIRMED" },
  stops: [{ id: "s-1", sequence: 1, destinationId: "d-1", destinationCode: "CLI-FER-01", address: "Av. Quito" }],
  assignments: [{ assignmentId: "a-1", orderId: "o-1", orderNumber: "OT-00000003", destinationId: "d-1",
    assignedWeightKg: 10, assignedVolumeM3: 1, assignedPallets: 1, wholeOrder: true }],
  exceptions: [], deliveries: [],
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn(async (input: string) => {
    const path = new URL(input).pathname;
    if (path.endsWith("/planning/trips/trip-1")) return json(DETAIL);
    if (path.endsWith("/planning/trips/trip-1/documents")) return json([{ orderId: "o-1", documents: [] }]);
    if (path.endsWith("/orders/o-1")) {
      return json({ id: "o-1", orderNumber: "OT-00000003", lines: [
        { id: "l-1", lineNumber: 1, materialCode: "PLP-700", materialDescription: "Gaveta", quantity: 40, uom: "UN" },
      ] });
    }
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
      <MemoryRouter initialEntries={["/trips/trip-1/print"]}>
        <Routes><Route path="/trips/:tripId/print" element={<TripRouteSheetPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const paths = () => fetchMock.mock.calls.map(([input]) => new URL(input as string).pathname);

describe("la página de la hoja de ruta", () => {
  it("con permiso de pedidos trae las líneas, y «Imprimir» abre la impresión del navegador", async () => {
    permissions = ["planning.trip:read", "orders.order:read"];
    const print = vi.fn();
    vi.stubGlobal("print", print);
    renderPage();

    expect(await screen.findByText("Gaveta")).toBeInTheDocument();
    expect(paths().some((p) => p.endsWith("/orders/o-1"))).toBe(true);
    await userEvent.setup().click(screen.getByRole("button", { name: "Imprimir" }));
    expect(print).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("link", { name: "Volver al viaje" })).toHaveAttribute("href", "/trips/trip-1");
  });

  it("sin permiso de pedidos no los pide y la hoja sale igual", async () => {
    permissions = ["planning.trip:read"];
    renderPage();

    expect(await screen.findByRole("article", { name: "Hoja de ruta VJ-00000002" })).toBeInTheDocument();
    expect(paths().some((p) => p.includes("/orders/") || p.endsWith("/documents"))).toBe(false);
  });
});
