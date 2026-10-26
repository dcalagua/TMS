import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { IntegrationScopeView } from "../../shared/api/integrationsApi";

/**
 * El formulario de credenciales (D4, QAS 2026-10-22): ofrecía 5 de los 12 alcances y el EWM no se
 * podía conectar desde la pantalla. Ahora los dibuja del catálogo del backend, agrupados, con una
 * plantilla que marca los del almacén de una vez.
 */

import { IntegrationClientDrawer } from "./IntegrationClientDrawer";

const scope = (code: string, group: string, requiresCarrier = false): IntegrationScopeView => ({ code, group, requiresCarrier });

/** Lo que devuelve `GET /integration-clients/scopes`, en el orden del enum. */
const CATALOGUE: IntegrationScopeView[] = [
  scope("integration.location:write", "ERP"),
  scope("integration.order:write", "ERP"),
  scope("integration.shipment:read", "WAREHOUSE"),
  scope("integration.tracking:write", "CARRIER"),
  scope("integration.tender:respond", "CARRIER", true),
  scope("integration.dispatch:write", "WAREHOUSE"),
  scope("integration.warehouse-milestone:write", "WAREHOUSE"),
  scope("integration.document:write", "ERP"),
  scope("integration.material:write", "WAREHOUSE"),
  scope("integration.order:read", "WAREHOUSE"),
  scope("integration.warehouse-order-event:write", "WAREHOUSE"),
  scope("integration.payable-payment:write", "FINANCE"),
];

const EWM_SCOPES = CATALOGUE.filter((entry) => entry.group === "WAREHOUSE").map((entry) => entry.code);

let fetchMock: ReturnType<typeof vi.fn>;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

beforeEach(() => {
  fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/integration-clients/scopes")) return json(CATALOGUE);
    if (url.pathname.endsWith("/fleet/carriers")) return json({ content: [], page: 0, size: 200, totalElements: 0 });
    if (url.pathname.endsWith("/integration-clients") && init?.method === "POST") {
      const body = JSON.parse(init.body as string) as { name: string; scopes: string[] };
      const client = {
        id: "c-1", clientId: "tms_abc", name: body.name, description: null, scopes: body.scopes, carrierId: null,
        active: true, lastUsedAt: null, secretRotatedAt: null, rotationGraceEndsAt: null, revokedAt: null,
        createdAt: "2026-10-22T00:00:00Z", updatedAt: "2026-10-22T00:00:00Z",
      };
      return json({ client, clientId: "tms_abc", secret: "s", bearerToken: "tms_abc.s", previousSecretValidUntil: null, notice: "" }, 201);
    }
    return new Response("{}", { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderDrawer(onCreated = vi.fn()) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <IntegrationClientDrawer companyId="company-1" client={null} onClose={vi.fn()} onCreated={onCreated} onUpdated={vi.fn()} />
    </QueryClientProvider>,
  );
  return onCreated;
}

describe("el formulario de credenciales", () => {
  it("ofrece los doce alcances del catálogo, agrupados y con su consecuencia en español", async () => {
    renderDrawer();

    const warehouse = await screen.findByRole("group", { name: "Almacén (EWM)" });
    expect(screen.getAllByRole("checkbox")).toHaveLength(12);
    expect(within(warehouse).getAllByRole("checkbox")).toHaveLength(6);
    expect(within(warehouse).getByText("Reportar despachos")).toBeInTheDocument();
    expect(within(warehouse).getByText("integration.warehouse-milestone:write")).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "ERP" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Transportista y telemetría" })).toBeInTheDocument();
    expect(within(screen.getByRole("group", { name: "Finanzas" })).getByText("Registrar pagos de facturas")).toBeInTheDocument();
  });

  it("la plantilla «conector EWM» marca los seis del almacén y la credencial se emite con ellos", async () => {
    const onCreated = renderDrawer();

    await userEvent.click(await screen.findByRole("button", { name: "Plantilla: conector EWM" }));
    const warehouse = screen.getByRole("group", { name: "Almacén (EWM)" });
    for (const checkbox of within(warehouse).getAllByRole("checkbox")) expect(checkbox).toBeChecked();
    expect(screen.getByRole("button", { name: "Plantilla: conector EWM" })).toBeDisabled();

    await userEvent.type(screen.getByRole("textbox", { name: "Nombre" }), "EWM PLAPASA");
    await userEvent.click(screen.getByRole("button", { name: "Emitir credencial" }));

    await waitFor(() => expect(onCreated).toHaveBeenCalled());
    const post = fetchMock.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "POST");
    expect(post).toBeDefined();
    const body = JSON.parse((post![1] as RequestInit).body as string) as { scopes: string[]; carrierId: string | null };
    expect(body.scopes.sort()).toEqual([...EWM_SCOPES].sort());
    expect(body.carrierId).toBeNull();
  });

  it("pide transportista sólo para el alcance que el catálogo marca como de transportista", async () => {
    renderDrawer();
    expect(screen.queryByRole("combobox", { name: /Transportista/ })).not.toBeInTheDocument();

    await userEvent.click(await screen.findByRole("checkbox", { name: /Responder ofertas/ }));
    expect(await screen.findByText("A quién pertenece la credencial que responde ofertas.")).toBeInTheDocument();
  });
});
