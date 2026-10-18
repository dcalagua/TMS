import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TaxRegimeView } from "../../shared/api/settlementApi";
import { EC_REGIME, GENERIC_REGIME } from "../../test/fixtures/taxRegimes";

/**
 * El régimen fiscal en la configuración de la empresa (ADR-027): lo que se ve resuelto, lo que
 * se guarda (sólo lo que la persona cambió; lo vacío hereda) y que sin permiso no se edita.
 */

const confirmDialog = vi.fn(async () => true);
vi.mock("../../lib/ui", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/ui")>()),
  confirmDialog: (...args: unknown[]) => confirmDialog(...(args as [])),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));

import { FiscalRegimeCard } from "./FiscalRegimeCard";

let served: TaxRegimeView = EC_REGIME;
let fetchMock: ReturnType<typeof vi.fn>;

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
}

beforeEach(() => {
  served = EC_REGIME;
  confirmDialog.mockClear();
  fetchMock = vi.fn(async (_input: string, init?: RequestInit) => {
    if (init?.method === "PUT") return json({ ...served, stored: JSON.parse(init.body as string) });
    return json(served);
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderCard(canManage: boolean) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <FiscalRegimeCard companyId="company-1" canManage={canManage} />
    </QueryClientProvider>,
  );
}

function puts() {
  return fetchMock.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT");
}

describe("el régimen fiscal de la empresa", () => {
  it("enseña el régimen resuelto de Ecuador: IVA, USD y sus dos retenciones", async () => {
    renderCard(true);
    expect(await screen.findByText("Ecuador · EC")).toBeInTheDocument();
    expect(screen.getByText("USD")).toBeInTheDocument();
    expect(screen.getByText("0, 5, 15 %")).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Retención en la fuente (IR)" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Retención de IVA" })).toBeInTheDocument();
    expect(screen.getByLabelText(/tasa de iva del flete/i)).toHaveAttribute("placeholder", "0");
  });

  it("habilitar la retención de IVA guarda sólo eso: el resto hereda", async () => {
    const user = userEvent.setup();
    renderCard(true);
    const group = await screen.findByRole("group", { name: "Retención de IVA" });
    await user.click(within(group).getByRole("combobox", { name: /se calcula/i }));
    await user.click(await screen.findByRole("option", { name: "Sí" }));
    await user.click(screen.getByRole("button", { name: /guardar régimen fiscal/i }));

    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(JSON.parse((puts()[0][1] as RequestInit).body as string)).toEqual({
      fiscalCountry: null, vatRate: null, freightVatRate: null,
      withholdings: [{ kind: "EC_VAT_WITHHOLDING", enabled: true, code: null, rate: null, thresholdAmount: null }],
    });
    expect(confirmDialog).not.toHaveBeenCalled();
  });

  it("cambiar de país se confirma y no manda las retenciones del país anterior", async () => {
    const user = userEvent.setup();
    renderCard(true);
    await user.type(await screen.findByLabelText(/país fiscal/i), "pe");
    expect(screen.getByText("Las retenciones del nuevo país se configuran después de guardar.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /guardar régimen fiscal/i }));

    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(confirmDialog).toHaveBeenCalled();
    expect(JSON.parse((puts()[0][1] as RequestInit).body as string)).toMatchObject({ fiscalCountry: "PE", withholdings: [] });
  });

  it("una tasa fuera de 0–100 no se guarda", async () => {
    const user = userEvent.setup();
    renderCard(true);
    await user.type(await screen.findByLabelText(/tasa general de iva/i), "120");
    expect(screen.getByRole("button", { name: /guardar régimen fiscal/i })).toBeDisabled();
  });

  it("sin permiso se ve pero no se edita", async () => {
    renderCard(false);
    expect(await screen.findByText("Ecuador · EC")).toBeInTheDocument();
    expect(screen.getByLabelText(/país fiscal/i)).toBeDisabled();
    expect(screen.queryByRole("button", { name: /guardar régimen fiscal/i })).not.toBeInTheDocument();
    expect(screen.getByText(/Sólo quien tiene permiso/)).toBeInTheDocument();
  });

  it("un país sin régimen propio no impone moneda ni calcula retenciones", async () => {
    served = GENERIC_REGIME;
    renderCard(true);
    expect(await screen.findByText("Genérico · CO")).toBeInTheDocument();
    expect(screen.getByText("Ninguna impuesta")).toBeInTheDocument();
    expect(screen.getByText("Este régimen no calcula retenciones.")).toBeInTheDocument();
  });
});
