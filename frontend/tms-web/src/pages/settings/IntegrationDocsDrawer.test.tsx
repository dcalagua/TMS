import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { IntegrationClientView, WebhookSubscriptionView } from "../../shared/api/integrationsApi";
import { InboundPanel } from "./InboundPanel";
import { CredentialDocsDrawer, SubscriptionDocsDrawer } from "./IntegrationDocsDrawer";
import { OutboundPanel } from "./OutboundPanel";

const CLIENT: IntegrationClientView = {
  id: "client-1",
  clientId: "tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q",
  name: "ERP SAP",
  description: null,
  scopes: ["integration.order:write", "integration.location:write", "integration.future:write"],
  carrierId: null,
  active: true,
  lastUsedAt: null,
  secretRotatedAt: null,
  rotationGraceEndsAt: null,
  revokedAt: null,
  createdAt: "2026-11-01T00:00:00Z",
  updatedAt: "2026-11-01T00:00:00Z",
};

const SUBSCRIPTION: WebhookSubscriptionView = {
  id: "sub-1",
  name: "WMS EWM",
  description: null,
  targetUrl: "https://wms.example.com/hooks/tms",
  eventTypes: ["ORDER_CANCELLED", "SHIPMENT_CONFIRMED"],
  active: true,
  suspendedReason: null,
  secretHint: "a1b2",
  secretRotatedAt: null,
  consecutiveFailures: 0,
  lastSuccessAt: null,
  lastFailureAt: null,
  createdAt: "2026-11-01T00:00:00Z",
  updatedAt: "2026-11-01T00:00:00Z",
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
}

/** Los bloques de código son texto preformateado: se buscan sin colapsar los saltos de línea. */
const RAW = { normalizer: (text: string) => text };

const page = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length });

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async (input: string) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/integration-clients")) return json(page([CLIENT]));
    if (url.pathname.endsWith("/webhooks")) return json(page([SUBSCRIPTION]));
    if (url.pathname.endsWith("/webhooks/health")) {
      return json({
        deliveriesPending: 0, oldestPendingAt: null, deliveriesFailed: 0, deliveriesProcessed: 0,
        inactiveSubscriptionsWithBacklog: 0, requestsSince: "2026-11-23T00:00:00Z",
        requestsSucceeded: 0, requestsPartial: 0, requestsRejected: 0, requestsFailed: 0,
      });
    }
    return json(page([]));
  }));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function withQueries(children: ReactNode) {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      {children}
    </QueryClientProvider>
  );
}

async function openRowMenu(rowText: string) {
  const user = userEvent.setup();
  const row = (await screen.findByText(rowText)).closest("tr") as HTMLElement;
  await user.click(within(row).getByRole("button", { name: "Abrir menú de acciones" }));
  return { user, items: screen.getAllByRole("menuitem").map((item) => item.textContent) };
}

describe("acción «Documentación» en Integraciones", () => {
  it("en Credenciales va justo debajo de «Editar» y abre los ejemplos de la credencial", async () => {
    render(withQueries(<InboundPanel companyId="company-1" canManage />));

    const { user, items } = await openRowMenu("ERP SAP");
    expect(items).toEqual(["Editar", "Documentación", "Rotar secreto", "Revocar"]);

    await user.click(screen.getByRole("menuitem", { name: "Documentación" }));
    const drawer = await screen.findByRole("dialog");
    expect(within(drawer).getByText("Documentación")).toBeInTheDocument();
    // Los alcances de la credencial, en el orden del catálogo, y el desconocido al final.
    const scopes = within(drawer).getAllByText(/^integration\./).map((node) => node.textContent);
    expect(scopes).toEqual(["integration.location:write", "integration.order:write", "integration.future:write"]);
  });

  it("en Suscripciones va justo debajo de «Editar»", async () => {
    render(withQueries(<OutboundPanel companyId="company-1" canManage />));

    const { items } = await openRowMenu("WMS EWM");
    expect(items).toEqual(["Editar", "Documentación", "Rotar secreto", "Desactivar"]);
  });

  it("sin permiso de gestión el menú solo ofrece «Documentación»", async () => {
    render(withQueries(<InboundPanel companyId="company-1" canManage={false} />));

    const { items } = await openRowMenu("ERP SAP");
    expect(items).toEqual(["Documentación"]);
  });
});

describe("CredentialDocsDrawer", () => {
  it("muestra petición y respuesta con el clientId real y nunca un secreto", async () => {
    render(<CredentialDocsDrawer client={CLIENT} onClose={() => {}} />);

    // El primer alcance viene abierto: su petición lleva la cabecera de la credencial.
    const request = await screen.findAllByText(/^POST http:\/\/.*\/integration\/v1\/locations\n/, RAW);
    expect(request[0].textContent).toContain(`Authorization: Bearer ${CLIENT.clientId}.<secreto>`);
    expect(request[0].textContent).toContain('"code": "ST-4711"');
    expect(screen.getAllByText(/Respuesta 201 Created/).length).toBeGreaterThan(0);
  });

  it("un alcance sin ejemplo lo dice en vez de fallar", async () => {
    const user = userEvent.setup();
    render(<CredentialDocsDrawer client={CLIENT} onClose={() => {}} />);

    await user.click(screen.getByText("integration.future:write"));
    expect(await screen.findByText("Sin ejemplo documentado para este alcance todavía.")).toBeInTheDocument();
  });
});

describe("SubscriptionDocsDrawer", () => {
  it("muestra lo que TMS manda a la URL de la suscripción y qué consultar después", async () => {
    render(<SubscriptionDocsDrawer subscription={SUBSCRIPTION} onClose={() => {}} />);

    // SHIPMENT_CONFIRMED va antes que ORDER_CANCELLED (orden del catálogo) y viene abierto.
    const sent = await screen.findByText(/^POST https:\/\/wms\.example\.com\/hooks\/tms\n/, RAW);
    expect(sent.textContent).toContain("X-TMS-Event-Type: SHIPMENT_CONFIRMED");
    expect(sent.textContent).toContain("X-TMS-Signature: t=");
    expect(sent.textContent).toContain('"type": "SHIPMENT_CONFIRMED"');
    expect(screen.getByText("integration.shipment:read")).toBeInTheDocument();
  });
});
