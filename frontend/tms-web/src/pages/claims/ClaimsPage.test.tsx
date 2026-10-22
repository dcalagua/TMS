import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ClaimDetail, ClaimDraft, ClaimSummary, ClaimsOverview } from "../../shared/api/claimsApi";

/**
 * Reclamos de carga (V71), con la API simulada en `fetch`.
 *
 * Lo que se protege: la lista pide por defecto los activos; quien sólo lee no ve cómo abrir uno;
 * desde «Registrar la entrega» el formulario llega prellenado con lo rechazado y envía la entrega y
 * el viaje - nunca un transportista elegido a mano -; y quien abrió el reclamo lee, antes de
 * intentarlo, que aceptarlo le corresponde a otra persona.
 */

let capabilities: string[] = ["CLAIMS_VIEW", "CLAIMS_MANAGE", "CLAIMS_DECIDE"];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "PLAPASA", name: "PLAPASA" },
    hasCapability: (c: string) => capabilities.includes(c),
    hasPermission: () => true,
  }),
}));

vi.mock("../../lib/ui", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/ui")>()),
  confirmDialog: vi.fn(async () => true),
  promptDialog: vi.fn(async () => "Duplicado"),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));

import { ClaimsPage } from "./ClaimsPage";

const SUMMARY: ClaimSummary = {
  id: "claim-1", claimNumber: "RC-000001", claimType: "DAMAGE", status: "UNDER_REVIEW", resolution: null,
  carrierId: "car-1", carrierName: "TRLIT Transportes", tripId: "trip-1", shipmentNumber: "SH-00000042",
  orderId: "ord-1", orderNumber: "TO-00000007", incidentDate: "2026-10-20", openedAt: "2026-10-20T15:00:00Z",
  ageDays: 2, claimedAmount: 45.5, acceptedAmount: null, currency: "USD", recoveryMethod: null,
};

const OVERVIEW: ClaimsOverview = {
  activeCount: 3, openCount: 1, underReviewCount: 1, awaitingRecovery: 1,
  currencies: [{ currency: "USD", count: 3, claimedAmount: 145.5, acceptedPending: 60 }],
  ageing: { upTo7: 2, from8To30: 0, from31To60: 1, over60: 0 },
};

const DRAFT: ClaimDraft = {
  tripId: "trip-1", shipmentNumber: "SH-00000042", carrierId: "car-1", carrierName: "TRLIT Transportes",
  claimable: true, notClaimableReason: null, orderId: "ord-1", orderNumber: "TO-00000007",
  orderDeliveryId: "del-1", deliveryResult: "PARTIAL", deliveryNotes: "Dos sillas rotas", refusedWeightKg: null,
  refusedPallets: null, tripExceptionId: null, exceptionType: null, exceptionNotes: null, suggestedType: "SHORTAGE",
  incidentDate: "2026-10-20",
  refusedLines: [{ orderLineId: "line-1", orderLineNumber: 1, materialCode: "SILLA-01", quantity: 2, uom: "UND", notes: null }],
  existingClaims: [],
};

const DETAIL: ClaimDetail = {
  summary: SUMMARY, description: "2 sillas rotas", orderDeliveryId: "del-1", tripExceptionId: null,
  openedBy: "user-1", openedByMe: true, submittedBy: "user-1", submittedAt: "2026-10-20T16:00:00Z",
  decisionReason: null, decidedBy: null, decidedAt: null, closedBy: null, closedAt: null, closeNotes: null,
  settlementInvoiceId: null, settlementInvoiceNumber: null, cancellationReason: null,
  lines: [], evidence: [], penalties: [],
  allowedActions: ["ACCEPT", "PARTIALLY_ACCEPT", "REJECT", "CANCEL", "ATTACH_EVIDENCE"], version: 1,
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  capabilities = ["CLAIMS_VIEW", "CLAIMS_MANAGE", "CLAIMS_DECIDE"];
  fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input);
    const path = url.pathname;
    if (path.endsWith("/settlement/claims") && init?.method === "POST") {
      return json({ ...DETAIL, summary: { ...SUMMARY, status: "OPEN" } }, 201);
    }
    if (path.endsWith("/settlement/claims")) return json({ content: [SUMMARY], page: 0, size: 25, totalElements: 1 });
    if (path.endsWith("/settlement/claims/overview")) return json(OVERVIEW);
    if (path.endsWith("/settlement/claims/draft")) return json(DRAFT);
    if (path.endsWith("/settlement/claims/claim-1")) return json(DETAIL);
    if (path.endsWith("/fleet/carriers")) return json({ content: [], page: 0, size: 200, totalElements: 0 });
    if (path.endsWith("/settlement/fiscal-settings")) return json({ fiscalCurrency: "USD" });
    return new Response("{}", { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderPage(path = "/settlement/claims") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <ClaimsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function calls(predicate: (url: URL, init?: RequestInit) => boolean) {
  return fetchMock.mock.calls.filter(([input, init]) => predicate(new URL(input as string), init as RequestInit | undefined));
}

describe("la lista de reclamos", () => {
  it("pide los activos por defecto y muestra los indicadores", async () => {
    renderPage();

    expect(await screen.findByText("RC-000001")).toBeInTheDocument();
    expect(screen.getByText("TRLIT Transportes")).toBeInTheDocument();
    expect(screen.getByText("En revisión")).toBeInTheDocument();
    const list = calls((url, init) => url.pathname.endsWith("/settlement/claims") && init?.method !== "POST")[0];
    expect(new URL(list[0] as string).searchParams.get("status"))
      .toBe("OPEN,UNDER_REVIEW,ACCEPTED,PARTIALLY_ACCEPTED,REJECTED");
    expect(await screen.findByText("1 abiertos · 1 en revisión")).toBeInTheDocument();
  });

  it("sin CLAIMS_MANAGE no ofrece abrir un reclamo", async () => {
    capabilities = ["CLAIMS_VIEW"];
    renderPage();

    expect(await screen.findByText("RC-000001")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /nuevo reclamo/i })).not.toBeInTheDocument();
  });
});

describe("abrir un reclamo desde una entrega", () => {
  it("llega prellenado con lo rechazado y envía la entrega, no un transportista", async () => {
    const user = userEvent.setup();
    renderPage("/settlement/claims?fromDelivery=del-1");

    const drawer = await screen.findByRole("dialog");
    expect(await within(drawer).findByDisplayValue(/Entrega parcial: pedido TO-00000007 del envío SH-00000042/)).toBeInTheDocument();
    expect(within(drawer).getByDisplayValue("SILLA-01 · línea 1")).toBeInTheDocument();
    const draftCall = calls((url) => url.pathname.endsWith("/settlement/claims/draft"))[0];
    expect(new URL(draftCall[0] as string).searchParams.get("orderDeliveryId")).toBe("del-1");

    // TMS no sabe cuánto vale una silla: el importe lo pone la persona.
    await user.type(within(drawer).getByLabelText(/^importe/i), "45.50");
    await user.click(within(drawer).getByRole("button", { name: /abrir reclamo/i }));

    await waitFor(() => expect(calls((url, init) => url.pathname.endsWith("/settlement/claims")
      && init?.method === "POST")).toHaveLength(1));
    const [, init] = calls((url, init) => url.pathname.endsWith("/settlement/claims") && init?.method === "POST")[0];
    const body = JSON.parse((init as RequestInit).body as string);
    expect(body).toMatchObject({
      tripId: "trip-1", orderId: "ord-1", orderDeliveryId: "del-1", claimType: "SHORTAGE", currency: "USD",
      claimedAmount: null,
      lines: [{ orderLineId: "line-1", quantity: 2, uom: "UND", amount: 45.5 }],
    });
    expect(body).not.toHaveProperty("carrierId");
  });
});

describe("la decisión", () => {
  it("quien abrió el reclamo lee que aceptarlo le corresponde a otra persona", async () => {
    const user = userEvent.setup();
    renderPage("/settlement/claims?claim=claim-1");

    const drawer = await screen.findByRole("dialog");
    await user.click(await within(drawer).findByRole("button", { name: /decidir/i }));
    expect(within(drawer).getByText(/aceptarlo le corresponde a otra persona/)).toBeInTheDocument();
  });
});
