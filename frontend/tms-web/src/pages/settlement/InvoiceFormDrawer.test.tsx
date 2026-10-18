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
  await user.type(within(drawer).getByLabelText(/importe total/i), "1000");
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
    expect(body).toMatchObject({ carrierId: "car-1", invoiceNumber: "F001-77", currency: "PEN", totalAmount: 1000 });
    expect(body.lines).toEqual([{
      tripId: null, chargeCode: "FREIGHT", description: "Flete Lima - Ica",
      quantity: null, unitAmount: null, lineAmount: 1000,
    }]);
  });
});
