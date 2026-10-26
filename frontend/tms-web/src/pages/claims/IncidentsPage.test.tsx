import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { IncidentCentreView } from "../../shared/api/claimsApi";

/**
 * Centro de incidencias (V71), con la API simulada en `fetch`.
 *
 * Lo que se protege: una incidencia y un reclamo conviven en la misma lista con su vocabulario y su
 * antigüedad; resolver una incidencia usa el endpoint de planificación con la nota que se pidió; y
 * «Abrir reclamo» lleva a los reclamos con la incidencia como origen. Sin permiso de ejecutar viajes
 * no se ofrece resolver.
 */

let permissions: string[] = ["planning.trip:execute"];
let capabilities: string[] = ["CLAIMS_VIEW", "CLAIMS_MANAGE"];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "PLAPASA", name: "PLAPASA" },
    hasCapability: (c: string) => capabilities.includes(c),
    hasPermission: (p: string) => permissions.includes(p),
  }),
}));

const promptDialog = vi.fn(async () => "Se liberó la vía" as string | null);
vi.mock("../../lib/ui", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/ui")>()),
  promptDialog: (...args: unknown[]) => promptDialog(...(args as [])),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));

import { IncidentsPage } from "./IncidentsPage";

const VIEW: IncidentCentreView = {
  rows: [
    {
      kind: "EXCEPTION", id: "exc-1", reference: "SH-00000042", type: "TRAFFIC_DELAY", status: "OPEN", open: true,
      tripId: "trip-1", shipmentNumber: "SH-00000042", carrierId: "car-1", carrierName: "TRLIT Transportes",
      occurredAt: "2026-09-01T10:00:00Z", closedAt: null, ageDays: 51, amount: null, currency: null,
      summary: "Paro en la Panamericana", claims: ["RC-000002"],
    },
    {
      kind: "CLAIM", id: "claim-1", reference: "RC-000001", type: "DAMAGE", status: "UNDER_REVIEW", open: true,
      tripId: "trip-2", shipmentNumber: "SH-00000043", carrierId: "car-1", carrierName: "TRLIT Transportes",
      occurredAt: "2026-10-20T10:00:00Z", closedAt: null, ageDays: 2, amount: 45.5, currency: "USD",
      summary: "2 sillas rotas", claims: [],
    },
  ],
  totals: { openExceptions: 1, activeClaims: 1, ageing: { upTo7: 1, from8To30: 0, from31To60: 1, over60: 0 } },
  truncated: false,
  limit: 200,
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  permissions = ["planning.trip:execute"];
  capabilities = ["CLAIMS_VIEW", "CLAIMS_MANAGE"];
  promptDialog.mockClear();
  fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/incidents")) return json(VIEW);
    if (url.pathname.endsWith("/exceptions/exc-1/resolve") && init?.method === "POST") return json({});
    if (url.pathname.endsWith("/fleet/carriers")) return json({ content: [], page: 0, size: 200, totalElements: 0 });
    return new Response("{}", { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function Where() {
  const location = useLocation();
  return <div data-testid="where">{`${location.pathname}${location.search}`}</div>;
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/incidents"]}>
        <Routes>
          <Route path="/incidents" element={<IncidentsPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("el centro de incidencias", () => {
  it("lista incidencias y reclamos juntos, con su tipo y su antigüedad, y pide los abiertos", async () => {
    renderPage();

    expect(await screen.findByText("RC-000001")).toBeInTheDocument();
    expect(screen.getByText("Paro en la Panamericana")).toBeInTheDocument();
    expect(screen.getByText("51 días")).toBeInTheDocument();
    expect(screen.getByText("Reclamado en RC-000002")).toBeInTheDocument();
    const incidentsCall = fetchMock.mock.calls.map(([input]) => new URL(input as string))
      .find((url) => url.pathname.endsWith("/incidents"));
    expect(incidentsCall?.searchParams.get("status")).toBe("OPEN");
  });

  it("resuelve una incidencia con el endpoint de planificación y la nota que se pidió", async () => {
    const user = userEvent.setup();
    renderPage();
    const row = (await screen.findByText("Paro en la Panamericana")).closest("tr") as HTMLElement;

    await user.click(within(row).getByRole("button", { name: /resolver/i }));

    await waitFor(() => expect(fetchMock.mock.calls.some(([input, init]) =>
      new URL(input as string).pathname.endsWith("/planning/trips/trip-1/exceptions/exc-1/resolve")
      && (init as RequestInit | undefined)?.method === "POST"
      && JSON.parse((init as RequestInit).body as string).notes === "Se liberó la vía")).toBe(true));
  });

  it("«Abrir reclamo» lleva a los reclamos con la incidencia como origen", async () => {
    const user = userEvent.setup();
    renderPage();
    const row = (await screen.findByText("Paro en la Panamericana")).closest("tr") as HTMLElement;

    await user.click(within(row).getByRole("button", { name: /abrir reclamo/i }));

    expect(await screen.findByTestId("where")).toHaveTextContent("/settlement/claims?fromException=exc-1");
  });

  it("sin permiso de ejecutar viajes no ofrece resolver", async () => {
    permissions = [];
    renderPage();
    const row = (await screen.findByText("Paro en la Panamericana")).closest("tr") as HTMLElement;

    expect(within(row).queryByRole("button", { name: /resolver/i })).not.toBeInTheDocument();
  });
});
