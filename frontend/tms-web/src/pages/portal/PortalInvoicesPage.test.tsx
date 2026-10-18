import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PortalInvoiceDetail, PortalInvoiceSummary } from "../../shared/api/portalApi";

/**
 * «Mis facturas» en el teléfono (fase 65): el estado que ve el transportista, el vencimiento, lo
 * pagado y el saldo; el detalle con líneas y pagos; y un «Sin conexión» sin drama si no hay señal.
 */

const COMPANY = "11111111-1111-4111-8111-111111111111";

vi.mock("../../shared/company/CompanyContext", () => ({
  CompanyProvider: ({ children }: { children: ReactNode }) => children,
  useCompany: () => ({ selected: { id: COMPANY } }),
}));

import { PortalInvoicesPage } from "./PortalInvoicesPage";
import { PortalInvoicePage } from "./PortalInvoicePage";

const PAID: PortalInvoiceSummary = {
  id: "inv-1", invoiceNumber: "F001-100", documentKind: "INVOICE", fiscalDocumentCode: "01", invoiceDate: "2026-09-01",
  currency: "PEN", totalAmount: 1300, status: "PAID", dueDate: "2026-10-01", paidAmount: 1180, openBalance: 0,
  withholdingTotalAmount: 120,
};
const IN_REVIEW: PortalInvoiceSummary = {
  ...PAID, id: "inv-2", invoiceNumber: "F001-101", status: "IN_REVIEW", dueDate: null, paidAmount: 0,
  openBalance: null, withholdingTotalAmount: null,
};
const ON_HOLD: PortalInvoiceSummary = { ...PAID, id: "inv-3", invoiceNumber: "F001-102", status: "ON_HOLD", paidAmount: 0, openBalance: 1180 };
const DETAIL: PortalInvoiceDetail = {
  ...PAID, vatLabel: "IGV", netAmount: 1300, payableAmount: 1180, rejectionReason: null,
  withholdings: [{
    kind: "PE_DETRACTION", label: "Detracción (SPOT)", code: "027", base: "TOTAL", baseAmount: 1300, rate: 4,
    amount: 52, certificateNumber: "CD-123", certificateDate: "2026-10-14",
  }],
  lines: [{ lineNumber: 1, description: "Flete Lima - Ica", lineAmount: 1300, chargeCode: "FREIGHT" }],
  payments: [{
    paymentReference: "TRX-555", paidOn: "2026-10-15", amount: 1180,
    withholdings: [{ kind: "PE_DETRACTION", amount: 52, certificateNumber: "CD-123", certificateDate: "2026-10-14" }],
  }],
};

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/portal/invoices" element={<PortalInvoicesPage />} />
          <Route path="/portal/invoices/:invoiceId" element={<PortalInvoicePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function serve(body: unknown) {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(body), {
    status: 200, headers: { "content-type": "application/json" },
  })));
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("mis facturas en el portal", () => {
  it("lista cada factura con el estado del transportista, vencimiento, pagado y saldo", async () => {
    serve([PAID, IN_REVIEW, ON_HOLD]);
    renderAt("/portal/invoices");

    const paid = await screen.findByRole("button", { name: "Abrir factura F001-100" });
    expect(within(paid).getByText("Pagada")).toBeInTheDocument();
    expect(within(paid).getByText(/Vence/)).toBeInTheDocument();
    expect(within(paid).getByText(/1[.,]?180[.,]00 PEN/)).toBeInTheDocument();
    expect(within(paid).getByText(/Retenciones 120[.,]00 PEN/)).toBeInTheDocument();

    const review = screen.getByRole("button", { name: "Abrir factura F001-101" });
    expect(within(review).getByText("En revisión")).toBeInTheDocument();
    expect(within(review).getByText("Sin vencimiento")).toBeInTheDocument();
    expect(within(screen.getByRole("button", { name: "Abrir factura F001-102" })).getByText("En espera")).toBeInTheDocument();

    const [input] = vi.mocked(fetch).mock.calls[0] as [string];
    expect(new URL(input).pathname).toMatch(/\/portal\/invoices$/);
  });

  it("el detalle enseña las líneas y los pagos con su constancia", async () => {
    serve(DETAIL);
    renderAt("/portal/invoices/inv-1");

    expect(await screen.findByRole("heading", { name: "F001-100" })).toBeInTheDocument();
    const payments = screen.getByRole("list", { name: "Pagos" });
    expect(within(payments).getByText("Referencia TRX-555")).toBeInTheDocument();
    expect(within(payments).getByText(/Comprobante CD-123/)).toBeInTheDocument();
    expect(screen.getByText("Detracción (SPOT)")).toBeInTheDocument();
    expect(screen.getByText("1. Flete Lima - Ica")).toBeInTheDocument();
  });

  it("una factura de Ecuador enseña el código del SRI y sus retenciones en dólares (ADR-027)", async () => {
    serve({
      ...DETAIL, invoiceNumber: "001-001-000000123", fiscalDocumentCode: "01", currency: "USD", vatLabel: "IVA",
      withholdings: [
        { kind: "EC_INCOME_TAX_WITHHOLDING", label: "Retención en la fuente (IR)", code: "310", base: "SUBTOTAL",
          baseAmount: 1300, rate: 1, amount: 13, certificateNumber: "001-001-000000045", certificateDate: "2026-10-14" },
      ],
      payments: [],
    });
    renderAt("/portal/invoices/inv-1");

    expect(await screen.findByRole("heading", { name: "001-001-000000123" })).toBeInTheDocument();
    expect(screen.getByText(/Factura \(01\)/)).toBeInTheDocument();
    expect(screen.getByText("Retención en la fuente (IR)")).toBeInTheDocument();
    expect(screen.getByText("Comprobante 001-001-000000045")).toBeInTheDocument();
    expect(screen.queryByText(/PEN|IGV|Detracción/)).not.toBeInTheDocument();
  });

  it("sin señal dice «Sin conexión» y ofrece reintentar", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))));
    renderAt("/portal/invoices");

    expect(await screen.findByText("Sin conexión")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();
  });
});
