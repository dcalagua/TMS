import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TolerancePolicyView } from "../../shared/api/settlementApi";
import { validateTolerance } from "./toleranceRules";

/**
 * Tolerancias de conciliación (fase 50), con la API simulada en `fetch`.
 *
 * Lo que se protege es lo que el servidor también rechazaría, dicho antes de enviar: un importe
 * absoluto sin moneda no es una tolerancia, y una política sin ningún límite tampoco. Y que quien
 * sólo puede leer no vea botones para escribir.
 */

let capabilities: string[] = ["SETTLEMENT_VIEW", "SETTLEMENT_MANAGE"];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "DEMO-LIMA", name: "Demo Lima" },
    hasCapability: (c: string) => capabilities.includes(c),
    hasPermission: () => true,
  }),
}));

import { TolerancePoliciesPage } from "./TolerancePoliciesPage";

const COMPANY_POLICY: TolerancePolicyView = {
  id: "tp-1", carrierId: null, carrierName: null, absoluteAmount: 5, percentage: 2,
  currency: "PEN", active: true, updatedAt: "2026-10-01T12:00:00Z",
};
const CARRIER_POLICY: TolerancePolicyView = {
  id: "tp-2", carrierId: "car-1", carrierName: "Transportes Andinos SAC", absoluteAmount: null,
  percentage: 1.5, currency: null, active: true, updatedAt: "2026-10-02T12:00:00Z",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  capabilities = ["SETTLEMENT_VIEW", "SETTLEMENT_MANAGE"];
  fetchMock = vi.fn(async (input: string) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/settlement/tolerance-policies")) return json([COMPANY_POLICY, CARRIER_POLICY]);
    if (url.pathname.endsWith("/fleet/carriers")) return json({ content: [], page: 0, size: 200, totalElements: 0 });
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
      <TolerancePoliciesPage />
    </QueryClientProvider>,
  );
}

function writes() {
  return fetchMock.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST");
}

describe("la pantalla de tolerancias", () => {
  it("lista las políticas, con la de empresa nombrada como tal, y explica la regla", async () => {
    renderPage();

    expect(await screen.findByText("Transportes Andinos SAC")).toBeInTheDocument();
    expect(screen.getByText("Toda la empresa")).toBeInTheDocument();
    expect(screen.getByText(/cabe en el importe absoluto o en el porcentaje/)).toBeInTheDocument();

    const listCall = new URL(fetchMock.mock.calls[0][0] as string);
    expect(listCall.searchParams.get("includeInactive")).toBe("false");
  });

  it("sin SETTLEMENT_MANAGE no ofrece crear, editar ni desactivar", async () => {
    capabilities = ["SETTLEMENT_VIEW"];
    renderPage();

    expect(await screen.findByText("Transportes Andinos SAC")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /nueva tolerancia/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /desactivar/i })).not.toBeInTheDocument();
  });

  it("un importe absoluto sin moneda se rechaza antes de enviarse", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Transportes Andinos SAC");

    await user.click(screen.getByRole("button", { name: /nueva tolerancia/i }));
    const drawer = await screen.findByRole("dialog");
    await user.type(within(drawer).getByLabelText(/importe absoluto/i), "10");
    await user.clear(within(drawer).getByLabelText(/moneda/i));
    await user.click(within(drawer).getByRole("button", { name: /crear tolerancia/i }));

    expect(await within(drawer).findByText("Un importe absoluto necesita su moneda.")).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });

  it("sin ningún límite no hay política que crear", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Transportes Andinos SAC");

    await user.click(screen.getByRole("button", { name: /nueva tolerancia/i }));
    const drawer = await screen.findByRole("dialog");
    await user.click(within(drawer).getByRole("button", { name: /crear tolerancia/i }));

    expect(await within(drawer).findByText(/Indica al menos un límite/)).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });

  it("enseña el mensaje del servidor cuando ya hay una política activa para ese ámbito", async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(async (input: string, init?: RequestInit) => {
      const url = new URL(input);
      if (url.pathname.endsWith("/settlement/tolerance-policies") && init?.method === "POST") {
        return new Response(JSON.stringify({
          status: 409, code: "conflict", detail: "Ya hay una tolerancia activa para toda la empresa.",
        }), { status: 409, headers: { "content-type": "application/problem+json" } });
      }
      if (url.pathname.endsWith("/settlement/tolerance-policies")) return json([COMPANY_POLICY]);
      return json({ content: [], page: 0, size: 200, totalElements: 0 });
    });
    renderPage();
    await screen.findByText("Toda la empresa");

    await user.click(screen.getByRole("button", { name: /nueva tolerancia/i }));
    const drawer = await screen.findByRole("dialog");
    await user.type(within(drawer).getByLabelText(/porcentaje/i), "3");
    await user.click(within(drawer).getByRole("button", { name: /crear tolerancia/i }));

    expect(await within(drawer).findByText("Ya hay una tolerancia activa para toda la empresa.")).toBeInTheDocument();
    expect(writes()).toHaveLength(1);
  });
});

describe("las reglas de una tolerancia", () => {
  const base = { scope: "COMPANY" as const, carrierId: "", absoluteAmount: "", percentage: "", currency: "" };

  it("el porcentaje solo basta, sin moneda", () => {
    expect(validateTolerance({ ...base, percentage: "2.5" })).toEqual({ fields: {}, form: null });
  });

  it("rechaza un porcentaje fuera de 0..100 y un importe negativo", () => {
    const verdict = validateTolerance({ ...base, percentage: "120", absoluteAmount: "-1", currency: "PEN" });
    expect(verdict.fields.percentage).toBeDefined();
    expect(verdict.fields.absoluteAmount).toBeDefined();
  });

  it("una política de transportista necesita el transportista", () => {
    expect(validateTolerance({ ...base, scope: "CARRIER", percentage: "1" }).fields.carrierId).toBeDefined();
  });
});
