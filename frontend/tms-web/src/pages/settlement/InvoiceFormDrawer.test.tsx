import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InvoiceFormDrawer } from "./InvoiceFormDrawer";

/**
 * Registrar una factura (fase 50): cantidad y precio unitario van juntos o ninguno, y cada línea
 * viaja con su código de cargo - `FREIGHT` si nadie eligió otro.
 */

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/fleet/carriers")) {
      return json({ content: [{ id: "car-1", businessName: "Transportes Andinos SAC" }], page: 0, size: 200, totalElements: 1 });
    }
    if (url.pathname.endsWith("/settlement/invoices") && init?.method === "POST") {
      return json({ id: "inv-9" }, 201);
    }
    if (url.pathname.endsWith("/settlement/fiscal-settings")) {
      return json({
        currency: "PEN", igvRate: 18, detractionEnabled: true, detractionCode: "027", detractionRate: 4,
        detractionThresholdAmount: 400, detractionStrictlyAboveThreshold: true, retentionAgent: false,
        retentionRate: 3, breakdownTolerance: 0.01,
      });
    }
    if (url.pathname.endsWith("/settlement/invoices")) {
      return json({
        content: [
          { id: "inv-1", invoiceNumber: "F001-10", invoiceDate: "2026-10-01", totalAmount: 1180, currency: "PEN",
            status: "APPROVED", documentType: "01" },
          { id: "inv-2", invoiceNumber: "07-FC01-3", invoiceDate: "2026-10-02", totalAmount: 118, currency: "PEN",
            status: "APPROVED", documentType: "07" },
        ],
        page: 0, size: 100, totalElements: 2,
      });
    }
    return json({});
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderForm(onSaved = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(
    <QueryClientProvider client={queryClient}>
      <InvoiceFormDrawer companyId="company-1" onClose={() => {}} onSaved={onSaved} />
    </QueryClientProvider>,
  );
  return onSaved;
}

async function fillHeader(user: ReturnType<typeof userEvent.setup>) {
  const drawer = await screen.findByRole("dialog");
  await user.click(within(drawer).getByRole("combobox", { name: /transportista/i }));
  await user.click(await screen.findByRole("option", { name: "Transportes Andinos SAC" }));
  await user.type(within(drawer).getByLabelText(/número de factura/i), "F001-77");
  // V66: en PEN el desglose es obligatorio; la tasa llega de la empresa y el botón compone IGV y total.
  await waitFor(() => expect(within(drawer).getByLabelText(/tasa igv/i)).toHaveValue(18));
  await user.type(within(drawer).getByLabelText(/subtotal/i), "1000");
  await user.click(within(drawer).getByRole("button", { name: /calcular igv y total/i }));
  await user.type(within(drawer).getByLabelText(/descripción/i), "Flete Lima - Ica");
  await user.type(within(drawer).getByLabelText(/importe de la línea/i), "1000");
  return drawer;
}

function posts() {
  return fetchMock.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST");
}

describe("registrar una factura", () => {
  it("una cantidad sin precio unitario no se envía", async () => {
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillHeader(user);

    await user.type(within(drawer).getByLabelText(/^cantidad/i), "2");
    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));

    expect((await within(drawer).findAllByText("Cantidad y precio unitario van juntos o ninguno.")).length).toBeGreaterThan(0);
    expect(posts()).toHaveLength(0);
  });

  it("manda la línea con su código de cargo y entrega la factura creada", async () => {
    const user = userEvent.setup();
    const onSaved = renderForm();
    const drawer = await fillHeader(user);

    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ id: "inv-9" })));
    const body = JSON.parse((posts()[0][1] as RequestInit).body as string);
    expect(body).toMatchObject({
      carrierId: "car-1", invoiceNumber: "F001-77", currency: "PEN", totalAmount: 1180, documentType: "01",
      subtotalAmount: 1000, igvRate: 18, igvAmount: 180,
    });
    expect(body.referencedInvoiceId).toBeUndefined();
    expect(body.lines).toEqual([{
      tripId: null, chargeCode: "FREIGHT", description: "Flete Lima - Ica",
      quantity: null, unitAmount: null, lineAmount: 1000,
    }]);
  });

  it("adelanta la detracción con las reglas de la empresa", async () => {
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillHeader(user);
    // 1180 x 4 % = 47,2: se deposita en soles enteros.
    expect(await within(drawer).findByText("Detracción 027 al 4 %: 47 PEN (se calcula, no se paga).")).toBeInTheDocument();
  });

  it("un RUC con el dígito verificador mal no se envía", async () => {
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillHeader(user);
    await user.type(within(drawer).getByLabelText(/^serie/i), "F001");
    await user.type(within(drawer).getByLabelText(/^correlativo/i), "77");
    await user.type(within(drawer).getByLabelText(/ruc emisor/i), "20100070971");
    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));

    expect(await within(drawer).findByText("RUC no válido: el dígito verificador no coincide.")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it("una nota de crédito enlaza la factura que corrige, y sólo facturas", async () => {
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillHeader(user);
    await user.click(within(drawer).getByRole("combobox", { name: /tipo de documento/i }));
    await user.click(await screen.findByRole("option", { name: /nota de crédito/i }));

    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));
    expect(await within(drawer).findByText("Una nota indica la factura que corrige.")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);

    await user.click(within(drawer).getByRole("combobox", { name: /factura que corrige/i }));
    expect(screen.queryByRole("option", { name: /07-FC01-3/ })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("option", { name: /F001-10/ }));
    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));

    await waitFor(() => expect(posts()).toHaveLength(1));
    const body = JSON.parse((posts()[0][1] as RequestInit).body as string);
    expect(body).toMatchObject({ documentType: "07", referencedInvoiceId: "inv-1" });
  });

  it("una penalidad va en negativo y con motivo", async () => {
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillHeader(user);
    await user.click(within(drawer).getByRole("combobox", { name: /código de cargo/i }));
    await user.click(await screen.findByRole("option", { name: "Penalidad" }));
    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));

    expect(await within(drawer).findByText("Una penalidad es una deducción: importe negativo.")).toBeInTheDocument();
    expect(await within(drawer).findByText("Una penalidad dice por qué se descuenta.")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });
});
