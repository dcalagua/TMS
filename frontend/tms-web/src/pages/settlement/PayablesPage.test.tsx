import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type {
  DuePayableRow, PayableAgingView, PayableView, PendingPaymentRequestRow,
} from "../../shared/api/payablesApi";

/**
 * Cuentas por pagar (fase 65), con la API simulada en `fetch`.
 *
 * Lo que se protege: los vencimientos marcan lo vencido, la antigüedad separa monedas, las
 * acciones sólo aparecen si el servidor las permite y quien mira tiene el permiso, registrar un
 * pago manda exactamente lo transcrito (con la moneda de la factura), y confirmar pasa por el
 * diálogo y muestra el rechazo del servidor tal cual.
 */

let capabilities: string[] = ["SETTLEMENT_VIEW", "PAYMENT_RECORD", "PAYMENT_CONFIRM"];

let profile: { id: string; email: string; fullName: string } | null = null;
vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "DEMO-LIMA", name: "Demo Lima" },
    profile,
    hasCapability: (c: string) => capabilities.includes(c),
    hasPermission: () => true,
  }),
}));

const confirmDialog = vi.fn(async () => true);
const promptDialog = vi.fn(async () => "Falta la constancia" as string | null);
const notifyError = vi.fn();
vi.mock("../../lib/ui", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/ui")>()),
  confirmDialog: (...args: unknown[]) => confirmDialog(...(args as [])),
  promptDialog: (...args: unknown[]) => promptDialog(...(args as [])),
  notifySuccess: vi.fn(),
  notifyError: (...args: unknown[]) => notifyError(...args),
}));

import { PayablesPage } from "./PayablesPage";

const OVERDUE: DuePayableRow = {
  invoiceId: "inv-1", invoiceNumber: "F001-100", carrierId: "car-1", carrierName: "Transportes Andinos SAC",
  currency: "PEN", status: "EXPORTED", invoiceDate: "2026-09-01", dueDate: "2026-10-01", daysToDue: -17,
  openBalance: 1180, paidAmount: 0, payableAmount: 1180,
};
const UPCOMING: DuePayableRow = {
  ...OVERDUE, invoiceId: "inv-2", invoiceNumber: "F001-101", status: "PARTIALLY_PAID", dueDate: "2026-10-25",
  daysToDue: 7, openBalance: 500, paidAmount: 680,
};
const AGING: PayableAgingView = {
  asOf: "2026-10-18",
  buckets: ["NOT_DUE", "DAYS_1_30", "DAYS_31_60", "DAYS_61_90", "DAYS_OVER_90", "NO_DUE_DATE"],
  totals: [
    { currency: "PEN", amounts: { NOT_DUE: 500, DAYS_1_30: 1180 }, total: 1680, count: 2 },
    { currency: "USD", amounts: { DAYS_OVER_90: 300 }, total: 300, count: 1 },
  ],
  carriers: [
    { carrierId: "car-1", carrierName: "Transportes Andinos SAC", currency: "PEN", amounts: { NOT_DUE: 500, DAYS_1_30: 1180 }, total: 1680, count: 2 },
  ],
};
const PAYABLE: PayableView = {
  invoiceId: "inv-1", invoiceNumber: "F001-100", documentType: "01", carrierId: "car-1",
  carrierName: "Transportes Andinos SAC", currency: "PEN", status: "EXPORTED", invoiceDate: "2026-09-01",
  dueDate: "2026-10-01", paymentTermsDays: 30, scheduledPaymentDate: null, onHoldReason: null,
  netAmount: 1300, detractionAmount: 52, detractionConstancyNumber: null, detractionConstancyDate: null,
  retentionAmount: 68, payableAmount: 1180, paidAmount: 0, openBalance: 1180, daysOverdue: 17,
  allowedActions: ["SCHEDULE", "HOLD", "REQUEST_PAYMENT"],
  payments: [],
  requests: [],
};
const PENDING: PendingPaymentRequestRow = {
  id: "req-1", invoiceId: "inv-1", invoiceNumber: "F001-100", carrierName: "Transportes Andinos SAC",
  currency: "PEN", amount: 1180, paymentReference: "TRX-555", paidOn: "2026-10-15",
  requestedBy: "11111111-aaaa-4bbb-8ccc-000000000001", requestedAt: "2026-10-15T15:00:00Z", openBalance: 1180,
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function problem(status: number, code: string, detail: string): Response {
  return new Response(JSON.stringify({ status, code, detail, title: "x" }), {
    status, headers: { "content-type": "application/problem+json" },
  });
}

let fetchMock: ReturnType<typeof vi.fn>;
let confirmResponse: () => Response;
let requestResponse: () => Response;

beforeEach(() => {
  capabilities = ["SETTLEMENT_VIEW", "PAYMENT_RECORD", "PAYMENT_CONFIRM"];
  profile = null;
  confirmDialog.mockClear();
  promptDialog.mockClear();
  notifyError.mockClear();
  confirmResponse = () => json(PAYABLE);
  requestResponse = () => json(PAYABLE, 201);
  fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input);
    const path = url.pathname;
    const method = init?.method ?? "GET";
    if (path.endsWith("/settlement/payables/due")) return json([OVERDUE, UPCOMING]);
    if (path.endsWith("/settlement/payables/aging")) return json(AGING);
    if (path.endsWith("/settlement/payables/payment-requests") && method === "GET") return json([PENDING]);
    if (path.endsWith("/payment-requests/req-1/confirm") && method === "POST") return confirmResponse();
    if (path.endsWith("/settlement/payables/inv-1/payment-requests") && method === "POST") return requestResponse();
    if (path.endsWith("/settlement/payables/inv-1/hold") && method === "POST") {
      return json({ ...PAYABLE, status: "ON_HOLD", onHoldReason: "Falta la constancia" });
    }
    if (path.endsWith("/settlement/payables/inv-1")) return json(PAYABLE);
    if (path.endsWith("/fleet/carriers")) return json({ content: [], page: 0, size: 200, totalElements: 0 });
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
      <PayablesPage />
    </QueryClientProvider>,
  );
}

function calls(pathSuffix: string, method = "GET") {
  return fetchMock.mock.calls.filter(([input, init]) =>
    new URL(input as string).pathname.endsWith(pathSuffix)
    && ((init as RequestInit | undefined)?.method ?? "GET") === method);
}

async function openDrawer() {
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByText("F001-100"));
  const drawer = await screen.findByRole("dialog");
  await within(drawer).findByText("Constancia pendiente");
  return { user, drawer };
}

describe("la pantalla de cuentas por pagar", () => {
  it("dice en el subtítulo que TMS no mueve dinero", async () => {
    renderPage();
    expect(screen.getByText(/TMS registra lo que el ERP pagó; no mueve dinero/)).toBeInTheDocument();
    await screen.findByText("F001-100");
  });

  it("lista los vencimientos, marca lo vencido y cambia el plazo", async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("F001-100")).toBeInTheDocument();
    expect(screen.getByText("F001-101")).toBeInTheDocument();
    expect(screen.getByText("Vencida hace 17 días")).toBeInTheDocument();
    expect(screen.getByText("Vence en 7 días")).toBeInTheDocument();
    expect(screen.getByText("Pago parcial")).toBeInTheDocument();
    expect(screen.getByText("1 facturas vencidas")).toBeInTheDocument();
    expect(new URL(calls("/settlement/payables/due")[0][0] as string).searchParams.get("within")).toBe("15");

    await user.click(screen.getByRole("button", { name: "30 días" }));
    await waitFor(() => {
      expect(new URL(calls("/settlement/payables/due").at(-1)![0] as string).searchParams.get("within")).toBe("30");
    });
  });

  it("la antigüedad separa los totales por moneda y por transportista", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("tab", { name: "Antigüedad" }));

    const totals = await screen.findByRole("table", { name: "Totales por moneda" });
    expect(within(totals).getByText("USD")).toBeInTheDocument();
    expect(within(totals).getByText("Por vencer")).toBeInTheDocument();
    expect(within(totals).getByText("Más de 90")).toBeInTheDocument();
    expect(within(totals).getAllByText(/1[.,]?680[.,]00\s?PEN/).length).toBeGreaterThan(0);
    const carriers = screen.getByRole("table", { name: "Por transportista" });
    expect(within(carriers).getByText("Transportes Andinos SAC")).toBeInTheDocument();
  });

  it("sin permiso de registrar, el detalle no ofrece ninguna acción", async () => {
    capabilities = ["SETTLEMENT_VIEW"];
    const { drawer } = await openDrawer();

    expect(within(drawer).getByText("Vencida hace 17 días")).toBeInTheDocument();
    expect(within(drawer).queryByRole("button", { name: "Registrar pago" })).not.toBeInTheDocument();
    expect(within(drawer).queryByRole("button", { name: "Retener" })).not.toBeInTheDocument();
    expect(within(drawer).queryByRole("button", { name: "Programar" })).not.toBeInTheDocument();
  });

  it("con permiso, ofrece sólo las acciones que el servidor permite; retener pide motivo", async () => {
    const { user, drawer } = await openDrawer();

    expect(within(drawer).getByRole("button", { name: "Registrar pago" })).toBeInTheDocument();
    expect(within(drawer).getByRole("button", { name: "Programar" })).toBeInTheDocument();
    expect(within(drawer).queryByRole("button", { name: "Liberar" })).not.toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Retener" }));
    expect(promptDialog).toHaveBeenCalledTimes(1);
    const hold = calls("/settlement/payables/inv-1/hold", "POST");
    expect(hold).toHaveLength(1);
    expect(JSON.parse((hold[0][1] as RequestInit).body as string)).toEqual({ reason: "Falta la constancia" });
  });

  it("registrar un pago manda lo transcrito con la moneda de la factura y avisa de los cuatro ojos", async () => {
    const { user, drawer } = await openDrawer();
    await user.click(within(drawer).getByRole("button", { name: "Registrar pago" }));

    const form = within(drawer).getByRole("form", { name: "Registrar pago" });
    expect(within(form).getByText(/no cuenta hasta que otra persona/)).toBeInTheDocument();
    expect(within(form).getByRole("textbox", { name: "Moneda" })).toHaveValue("PEN");
    expect(within(form).getByRole("textbox", { name: "Moneda" })).toHaveAttribute("readonly");

    await user.type(within(form).getByRole("textbox", { name: /Referencia de pago/ }), "TRX-777");
    fireEvent.change(within(form).getByLabelText(/Fecha de pago/), { target: { value: "2026-10-16" } });
    const amount = within(form).getByRole("spinbutton", { name: /^Monto\s*\*?$/ });
    await user.clear(amount);
    await user.type(amount, "1000");
    await user.type(within(form).getByRole("textbox", { name: /Constancia de detracción/ }), "CD-123");
    fireEvent.change(within(form).getByLabelText(/Fecha de constancia/), { target: { value: "2026-10-16" } });
    await user.type(within(form).getByRole("spinbutton", { name: /Monto de detracción/ }), "52");
    await user.click(within(form).getByRole("button", { name: "Enviar a confirmar" }));

    await waitFor(() => expect(calls("/settlement/payables/inv-1/payment-requests", "POST")).toHaveLength(1));
    const [, init] = calls("/settlement/payables/inv-1/payment-requests", "POST")[0];
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      paymentReference: "TRX-777",
      paidOn: "2026-10-16",
      amount: 1000,
      currency: "PEN",
      detractionConstancyNumber: "CD-123",
      detractionConstancyDate: "2026-10-16",
      detractionAmount: 52,
      retentionAmount: null,
    });
  });

  it("un pago que excede el saldo muestra la razón del servidor en el formulario", async () => {
    requestResponse = () => problem(422, "payment-exceeds-balance", "El pago de 2000.00 excede el saldo de 1180.00.");
    const { user, drawer } = await openDrawer();
    await user.click(within(drawer).getByRole("button", { name: "Registrar pago" }));
    const form = within(drawer).getByRole("form", { name: "Registrar pago" });
    await user.type(within(form).getByRole("textbox", { name: /Referencia de pago/ }), "TRX-1");
    await user.click(within(form).getByRole("button", { name: "Enviar a confirmar" }));

    expect(await within(form).findByText("El pago de 2000.00 excede el saldo de 1180.00.")).toBeInTheDocument();
  });

  it("confirmar un pago pendiente pasa por el diálogo y llama a la API", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("tab", { name: "Pagos por confirmar" }));
    await screen.findByText("TRX-555");

    await user.click(screen.getByRole("button", { name: "Confirmar" }));

    expect(confirmDialog).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(calls("/settlement/payables/inv-1/payment-requests/req-1/confirm", "POST")).toHaveLength(1));
  });

  it("si quien confirma es quien lo pidió, se muestra el mensaje del servidor", async () => {
    confirmResponse = () => problem(409, "conflict", "Quien registró el pago no puede confirmarlo.");
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("tab", { name: "Pagos por confirmar" }));
    await screen.findByText("TRX-555");
    await user.click(screen.getByRole("button", { name: "Confirmar" }));

    await waitFor(() => expect(notifyError).toHaveBeenCalledWith("No se pudo completar", "Quien registró el pago no puede confirmarlo."));
  });

  it("sin permiso de confirmar, la bandeja no ofrece confirmar ni rechazar", async () => {
    capabilities = ["SETTLEMENT_VIEW", "PAYMENT_RECORD"];
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("tab", { name: "Pagos por confirmar" }));
    await screen.findByText("TRX-555");

    expect(screen.queryByRole("button", { name: "Confirmar" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Rechazar" })).not.toBeInTheDocument();
  });

  it("quien registró el pago no ve el botón de confirmarlo, y la columna dice 'Tú'", async () => {
    profile = { id: "11111111-aaaa-4bbb-8ccc-000000000001", email: "me@example.invalid", fullName: "Me" };
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("tab", { name: "Pagos por confirmar" }));
    await screen.findByText("TRX-555");

    expect(screen.queryByRole("button", { name: "Confirmar" })).not.toBeInTheDocument();
    expect(screen.getByText("Lo confirma otra persona")).toBeInTheDocument();
    expect(screen.getByText("Tú")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Rechazar" })).toBeInTheDocument();
  });
});
