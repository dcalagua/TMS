import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InvoiceFormDrawer } from "./InvoiceFormDrawer";
import type { TaxRegimeView } from "../../shared/api/settlementApi";
import { EC_PRIVATE_RUC, EC_REGIME, GENERIC_REGIME, PE_REGIME } from "../../test/fixtures/taxRegimes";

/**
 * Registrar una factura (fase 50): cantidad y precio unitario van juntos o ninguno, y cada línea
 * viaja con su código de cargo - `FREIGHT` si nadie eligió otro. Lo fiscal sale del régimen de la
 * empresa (ADR-027): Perú, Ecuador o genérico, sin una tasa ni un código escrito en la pantalla.
 */

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;
let regime: TaxRegimeView = PE_REGIME;

beforeEach(() => {
  regime = PE_REGIME;
  fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/fleet/carriers")) {
      return json({ content: [{ id: "car-1", businessName: "Transportes Andinos SAC" }], page: 0, size: 200, totalElements: 1 });
    }
    if (url.pathname.endsWith("/settlement/invoices") && init?.method === "POST") {
      return json({ id: "inv-9" }, 201);
    }
    if (url.pathname.endsWith("/settlement/fiscal-settings")) {
      return json(regime);
    }
    if (url.pathname.endsWith("/settlement/invoices")) {
      return json({
        content: [
          { id: "inv-1", invoiceNumber: "F001-10", invoiceDate: "2026-10-01", totalAmount: 1180, currency: "PEN",
            status: "APPROVED", documentKind: "INVOICE", fiscalDocumentCode: "01" },
          { id: "inv-2", invoiceNumber: "07-FC01-3", invoiceDate: "2026-10-02", totalAmount: 118, currency: "PEN",
            status: "APPROVED", documentKind: "CREDIT_NOTE", fiscalDocumentCode: "07" },
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
  // En la moneda fiscal el desglose es obligatorio; la tasa llega del régimen y el botón compone impuesto y total.
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
      carrierId: "car-1", invoiceNumber: "F001-77", currency: "PEN", totalAmount: 1180, documentKind: "INVOICE",
      subtotalAmount: 1000, taxRate: 18, taxAmount: 180,
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
    expect(await within(drawer).findByText("Detracción (SPOT) 027 al 4 %: 47 PEN (se calcula, no se paga).")).toBeInTheDocument();
  });

  it("un RUC con el dígito verificador mal no se envía", async () => {
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillHeader(user);
    await user.type(within(drawer).getByLabelText(/número del comprobante/i), "F001-77");
    await user.type(within(drawer).getByLabelText(/ruc emisor/i), "20100070971");
    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));

    expect(await within(drawer).findByText("RUC no válido: el dígito verificador no coincide.")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it("una nota de crédito enlaza la factura que corrige, y sólo facturas", async () => {
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillHeader(user);
    await user.click(within(drawer).getByRole("combobox", { name: /tipo de comprobante/i }));
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
    expect(body).toMatchObject({ documentKind: "CREDIT_NOTE", referencedInvoiceId: "inv-1" });
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

describe("una factura de Ecuador (PLAPASA)", () => {
  async function fillEcuador(user: ReturnType<typeof userEvent.setup>, subtotal = "1250") {
    const drawer = await screen.findByRole("dialog");
    await user.click(within(drawer).getByRole("combobox", { name: /transportista/i }));
    await user.click(await screen.findByRole("option", { name: "Transportes Andinos SAC" }));
    // La moneda fiscal y la tasa del flete (0 %, LRTI art. 56) llegan del régimen.
    await waitFor(() => expect(within(drawer).getByLabelText(/^moneda/i)).toHaveValue("USD"));
    expect(within(drawer).getByLabelText(/tasa iva/i)).toHaveValue(0);
    await user.type(within(drawer).getByLabelText(/subtotal/i), subtotal);
    await user.click(within(drawer).getByRole("button", { name: /calcular iva y total/i }));
    await user.type(within(drawer).getByLabelText(/descripción/i), "Flete Guayaquil - Quito");
    await user.type(within(drawer).getByLabelText(/importe de la línea/i), subtotal);
    return drawer;
  }

  beforeEach(() => {
    regime = EC_REGIME;
  });

  it("habla de IVA, ofrece los códigos del SRI y pide el número 001-001-000000123", async () => {
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillEcuador(user);
    expect(within(drawer).queryByText(/IGV/)).not.toBeInTheDocument();
    expect(within(drawer).getByLabelText(/número del comprobante/i)).toHaveAttribute("placeholder", "001-001-000000123");
    await user.click(within(drawer).getByRole("combobox", { name: /tipo de comprobante/i }));
    expect(await screen.findByRole("option", { name: "Nota de crédito (04)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Nota de débito (05)" })).toBeInTheDocument();
  });

  it("adelanta la retención en la fuente del IR (310, 1 %) y no la de IVA, que la empresa no tiene", async () => {
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillEcuador(user);
    expect(await within(drawer).findByText("Retención en la fuente (IR) 310 al 1 %: 12,5 USD (se calcula, no se paga).")).toBeInTheDocument();
    expect(within(drawer).queryByText(/Retención de IVA/)).not.toBeInTheDocument();
  });

  it("con la retención de IVA habilitada adelanta el 70 % del IVA, al lado de la del IR", async () => {
    regime = {
      ...EC_REGIME,
      withholdings: EC_REGIME.withholdings.map((rule) => rule.kind === "EC_VAT_WITHHOLDING" ? { ...rule, enabled: true } : rule),
    };
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillEcuador(user, "1000");
    // Un servicio con IVA 15 %: 150 de IVA, 105 retenidos.
    await user.clear(within(drawer).getByLabelText(/tasa iva/i));
    await user.type(within(drawer).getByLabelText(/tasa iva/i), "15");
    await user.click(within(drawer).getByRole("button", { name: /calcular iva y total/i }));
    expect(await within(drawer).findByText("Retención de IVA al 70 %: 105 USD (se calcula, no se paga).")).toBeInTheDocument();
    expect(within(drawer).getByText("Retención en la fuente (IR) 310 al 1 %: 10 USD (se calcula, no se paga).")).toBeInTheDocument();
  });

  it("valida el RUC de 13 dígitos y el formato del número, y manda el comprobante", async () => {
    const user = userEvent.setup();
    const onSaved = renderForm();
    const drawer = await fillEcuador(user);
    await user.type(within(drawer).getByLabelText(/número del comprobante/i), "F001-77");
    await user.type(within(drawer).getByLabelText(/ruc emisor/i), "0990004197001");
    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));
    expect(await within(drawer).findByText("No tiene el formato del país, p. ej. 001-001-000000123.")).toBeInTheDocument();
    expect(within(drawer).getByText("RUC no válido: el dígito verificador no coincide.")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);

    await user.clear(within(drawer).getByLabelText(/número del comprobante/i));
    await user.type(within(drawer).getByLabelText(/número del comprobante/i), "001-001-000000123");
    await user.clear(within(drawer).getByLabelText(/ruc emisor/i));
    await user.type(within(drawer).getByLabelText(/ruc emisor/i), EC_PRIVATE_RUC);
    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const body = JSON.parse((posts()[0][1] as RequestInit).body as string);
    expect(body).toMatchObject({
      currency: "USD", documentKind: "INVOICE", documentNumber: "001-001-000000123", issuerTaxId: EC_PRIVATE_RUC,
      subtotalAmount: 1250, taxRate: 0, taxAmount: 0, totalAmount: 1250,
    });
    expect(body.invoiceNumber).toBeNull();
  });

  it("una tasa que el régimen no admite no se envía", async () => {
    const user = userEvent.setup();
    renderForm();
    const drawer = await fillEcuador(user);
    await user.clear(within(drawer).getByLabelText(/tasa iva/i));
    await user.type(within(drawer).getByLabelText(/tasa iva/i), "12");
    await user.type(within(drawer).getByLabelText(/número de factura/i), "X-1");
    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));
    expect(await within(drawer).findByText(/Tasa no admitida por el régimen fiscal/)).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });
});

describe("un país sin régimen propio", () => {
  beforeEach(() => {
    regime = GENERIC_REGIME;
  });

  it("no impone moneda ni desglose, y no adelanta ninguna retención", async () => {
    const user = userEvent.setup();
    const onSaved = renderForm();
    const drawer = await screen.findByRole("dialog");
    await user.click(within(drawer).getByRole("combobox", { name: /transportista/i }));
    await user.click(await screen.findByRole("option", { name: "Transportes Andinos SAC" }));
    await user.type(within(drawer).getByLabelText(/^moneda/i), "COP");
    await user.type(within(drawer).getByLabelText(/importe total/i), "500000");
    await user.type(within(drawer).getByLabelText(/número del comprobante/i), "FE-2026/0042");
    await user.type(within(drawer).getByLabelText(/identificación fiscal emisor/i), "900123456-7");
    await user.type(within(drawer).getByLabelText(/descripción/i), "Flete Bogotá - Cali");
    await user.type(within(drawer).getByLabelText(/importe de la línea/i), "500000");
    expect(within(drawer).queryByText(/se calcula, no se paga/)).not.toBeInTheDocument();
    await user.click(within(drawer).getByRole("button", { name: /registrar factura/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const body = JSON.parse((posts()[0][1] as RequestInit).body as string);
    expect(body).toMatchObject({ currency: "COP", documentNumber: "FE-2026/0042", issuerTaxId: "900123456-7" });
    expect(body.subtotalAmount).toBeUndefined();
  });
});
