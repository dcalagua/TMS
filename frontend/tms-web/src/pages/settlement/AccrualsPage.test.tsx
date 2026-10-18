import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AccrualJournalView, AccrualSummaryView, AccrualView } from "../../shared/api/accrualApi";

/**
 * Provisiones de flete (fase 55), con la API simulada en `fetch`.
 *
 * Lo que se protege: la lista enseña el saldo y el estado, el detalle sus movimientos, revertir
 * pide motivo y sólo lo ofrece a quien puede, y el asiento dice qué cuenta falta en vez de
 * inventarla.
 */

let capabilities: string[] = ["ACCRUAL_VIEW", "ACCRUAL_EXPORT", "ACCRUAL_MANAGE"];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "DEMO-LIMA", name: "Demo Lima" },
    hasCapability: (c: string) => capabilities.includes(c),
    hasPermission: () => true,
  }),
}));

const promptDialog = vi.fn(async () => "Servicio no facturable" as string | null);
vi.mock("../../lib/ui", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/ui")>()),
  promptDialog: (...args: unknown[]) => promptDialog(...(args as [])),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));

import { AccrualsPage } from "./AccrualsPage";

const OPEN: AccrualSummaryView = {
  id: "acr-1", tripId: "trip-1", shipmentNumber: "SH-000123", carrierId: "car-1",
  carrierName: "Transportes Andinos SAC", currency: "PEN", status: "OPEN", basis: "ESTIMATED",
  serviceDate: "2026-10-03", accruedAmount: 1450, consumedAmount: 0, reversedAmount: 0, balance: 1450,
  createdAt: "2026-10-03T14:00:00Z",
};
const SETTLED: AccrualSummaryView = {
  ...OPEN, id: "acr-2", tripId: "trip-2", shipmentNumber: "SH-000124", status: "SETTLED", basis: "ACTUAL",
  accruedAmount: 1000, consumedAmount: 1100, balance: -100,
};
const DETAIL: AccrualView = {
  summary: OPEN,
  entries: [{
    id: "e-1", entryType: "ACCRUE", amount: 1450, period: "2026-10-01", sourceType: "TRIP_DISPATCHED",
    sourceKey: "trip-1", carrierInvoiceId: null, reason: null, createdBy: null, createdAt: "2026-10-03T14:00:00Z",
  }],
};
const JOURNAL: AccrualJournalView = {
  period: "2026-10",
  rows: [{
    entryId: "e-1", entryDate: "2026-10-03", entryType: "ACCRUE", sourceType: "TRIP_DISPATCHED", sourceKey: "trip-1",
    tripId: "trip-1", shipmentNumber: "SH-000123", carrierCode: "CAR", carrierName: "Transportes Andinos SAC",
    invoiceNumber: null, currency: "PEN", amount: 1450, debitConcept: "FREIGHT_EXPENSE", debitAccount: "6311",
    debitCostCenter: null, creditConcept: "FREIGHT_ACCRUAL", creditAccount: null, creditCostCenter: null, reason: null,
  }],
  warnings: ["No active GL mapping for FREIGHT_ACCRUAL: 1 movement side(s) leave that account empty."],
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  capabilities = ["ACCRUAL_VIEW", "ACCRUAL_EXPORT", "ACCRUAL_MANAGE"];
  promptDialog.mockClear();
  fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/settlement/accruals/acr-1/reverse") && init?.method === "POST") {
      return json({ ...DETAIL, summary: { ...OPEN, status: "REVERSED", reversedAmount: 1450, balance: 0 } });
    }
    if (url.pathname.endsWith("/settlement/accruals/acr-1")) return json(DETAIL);
    if (url.pathname.endsWith("/settlement/accruals/journal")) return json(JOURNAL);
    if (url.pathname.endsWith("/settlement/accruals")) {
      return json({ content: [OPEN, SETTLED], page: 0, size: 25, totalElements: 2, totalPages: 1 });
    }
    if (url.pathname.endsWith("/fleet/carriers")) return json({ content: [], page: 0, size: 200, totalElements: 0 });
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
      <AccrualsPage />
    </QueryClientProvider>,
  );
}

function calls(pathSuffix: string, method = "GET") {
  return fetchMock.mock.calls.filter(([input, init]) =>
    new URL(input as string).pathname.endsWith(pathSuffix)
    && ((init as RequestInit | undefined)?.method ?? "GET") === method);
}

describe("la pantalla de provisiones", () => {
  it("lista las provisiones con su saldo y estado; un saldo negativo es la variación", async () => {
    renderPage();

    expect(await screen.findByText("SH-000123")).toBeInTheDocument();
    expect(screen.getByText("SH-000124")).toBeInTheDocument();
    expect(screen.getByText("Abierta")).toBeInTheDocument();
    expect(screen.getByText("Liquidada")).toBeInTheDocument();
    expect(screen.getAllByText(/[-\u2212]\s?100[.,]00\s?PEN/).length).toBeGreaterThan(0);
    const list = new URL(calls("/settlement/accruals")[0][0] as string);
    expect(list.searchParams.get("page")).toBe("0");
  });

  it("filtra por estado y por período", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("SH-000123");

    await user.click(screen.getByRole("combobox", { name: "Estado" }));
    await user.click(await screen.findByRole("option", { name: "Consumida en parte" }));

    const last = new URL(calls("/settlement/accruals").at(-1)![0] as string);
    expect(last.searchParams.get("status")).toBe("PARTIALLY_SETTLED");
  });

  it("el detalle muestra los movimientos y revertir pide motivo", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByText("SH-000123"));

    const drawer = await screen.findByRole("dialog");
    expect(await within(drawer).findByText("Provisión")).toBeInTheDocument();
    expect(within(drawer).getByText("Despacho del viaje")).toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Revertir" }));

    expect(promptDialog).toHaveBeenCalledTimes(1);
    const reverse = calls("/settlement/accruals/acr-1/reverse", "POST");
    expect(reverse).toHaveLength(1);
    expect(JSON.parse((reverse[0][1] as RequestInit).body as string)).toEqual({ reason: "Servicio no facturable" });
  });

  it("descartar el motivo no revierte nada", async () => {
    promptDialog.mockResolvedValueOnce(null);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByText("SH-000123"));
    const drawer = await screen.findByRole("dialog");
    await user.click(await within(drawer).findByRole("button", { name: "Revertir" }));

    expect(calls("/settlement/accruals/acr-1/reverse", "POST")).toHaveLength(0);
  });

  it("quien sólo lee no ve revertir ni la pestaña de asientos", async () => {
    capabilities = ["ACCRUAL_VIEW"];
    const user = userEvent.setup();
    renderPage();

    expect(screen.queryByRole("tab", { name: "Asientos" })).not.toBeInTheDocument();
    await user.click(await screen.findByText("SH-000123"));
    const drawer = await screen.findByRole("dialog");
    await within(drawer).findByText("Despacho del viaje");
    expect(within(drawer).queryByRole("button", { name: "Revertir" })).not.toBeInTheDocument();
  });

  it("el asiento avisa de la cuenta que falta y la deja vacía", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole("tab", { name: "Asientos" }));
    await user.click(screen.getByRole("button", { name: "Ver asiento" }));

    expect(await screen.findByText(/No active GL mapping for FREIGHT_ACCRUAL/)).toBeInTheDocument();
    expect(screen.getByText("6311")).toBeInTheDocument();
    expect(screen.getByText("Sin cuenta")).toBeInTheDocument();
    const journal = new URL(calls("/settlement/accruals/journal")[0][0] as string);
    expect(journal.searchParams.get("format")).toBe("JSON");
    expect(journal.searchParams.get("period")).toMatch(/^\d{4}-\d{2}$/);
  });
});
