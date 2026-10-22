import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { IssuedTrackingLinkView, TrackingLinkView } from "../../shared/api/trackingLinksApi";

/**
 * «Compartir seguimiento» en la oficina (ADR-029): generar, copiar, WhatsApp, revocar, con la API
 * simulada en `fetch`. Lo que se protege: el token aparece una vez y sólo tras generarlo, el mensaje
 * de WhatsApp lleva el enlace, y revocar pide un motivo.
 */

let permissions: string[] = ["orders.order:read", "orders.tracking_link:manage"];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "PLAPASA", name: "PLAPASA" },
    hasCapability: () => true,
    hasPermission: (p: string) => permissions.includes(p),
  }),
}));

const promptAnswer = { value: "enviado al cliente equivocado" as string | null };
vi.mock("../../lib/ui", () => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  promptDialog: vi.fn(async () => promptAnswer.value),
}));

import { renderHook } from "@testing-library/react";
import { TrackingLinksPanel } from "./TrackingLinksPanel";
import { trackingMessage, useCanShareTracking } from "./trackingLinks";

const TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";

const ACTIVE: TrackingLinkView = {
  id: "link-1", state: "ACTIVE", createdAt: "2026-10-22T14:00:00Z", createdBy: "user-1",
  expiresAt: "2026-11-21T14:00:00Z", revokedAt: null, revokeReason: null, lastViewedAt: null,
  viewCount: 0, version: 0,
};

let links: TrackingLinkView[];
let fetchMock: ReturnType<typeof vi.fn>;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

beforeEach(() => {
  permissions = ["orders.order:read", "orders.tracking_link:manage"];
  promptAnswer.value = "enviado al cliente equivocado";
  links = [];
  fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/orders/order-1/tracking-links") && (init?.method ?? "GET") === "GET") {
      return json(links);
    }
    if (url.pathname.endsWith("/orders/order-1/tracking-links") && init?.method === "POST") {
      links = [ACTIVE];
      const issued: IssuedTrackingLinkView = { link: ACTIVE, token: TOKEN, path: `/t/${TOKEN}` };
      return json(issued, 201);
    }
    if (url.pathname.endsWith("/tracking-links/link-1/revoke")) {
      links = [{ ...ACTIVE, state: "REVOKED", revokedAt: "2026-10-22T15:00:00Z", revokeReason: "x" }];
      return json(links[0]);
    }
    return new Response("{}", { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <TrackingLinksPanel companyId="company-1" orderId="order-1" orderNumber="SO-2026-10045" />
    </QueryClientProvider>,
  );
}

describe("TrackingLinksPanel", () => {
  it("generates a link, shows it once, and opens WhatsApp with a Spanish message that carries it", async () => {
    renderPanel();
    expect(await screen.findByText("Este pedido todavía no tiene enlaces de seguimiento.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Generar enlace" }));

    const field = await screen.findByLabelText("Enlace de seguimiento");
    const url = `${window.location.origin}/t/${TOKEN}`;
    expect(field).toHaveValue(url);

    const whatsApp = screen.getByRole("link", { name: /Enviar por WhatsApp/ });
    const href = whatsApp.getAttribute("href") ?? "";
    expect(href.startsWith("https://wa.me/?text=")).toBe(true);
    const text = decodeURIComponent(href.slice("https://wa.me/?text=".length));
    expect(text).toBe(`Hola. Puede seguir su pedido SO-2026-10045 de PLAPASA en este enlace: ${url}`);
    expect(whatsApp).toHaveAttribute("target", "_blank");
    expect(whatsApp).toHaveAttribute("rel", expect.stringContaining("noopener"));

    const post = fetchMock.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "POST");
    expect(post).toBeDefined();
    expect(new Headers((post![1] as RequestInit).headers).get("X-Company-Id")).toBe("company-1");
  });

  it("copies the link to the clipboard", async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    renderPanel();
    await userEvent.click(await screen.findByRole("button", { name: "Generar enlace" }));
    await screen.findByLabelText("Enlace de seguimiento");

    await userEvent.click(screen.getAllByRole("button", { name: "Copiar enlace" })[0]);
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/t/${TOKEN}`);
  });

  it("the list never carries the token, and revoking asks for a reason and sends it", async () => {
    links = [ACTIVE];
    renderPanel();

    const list = await screen.findByRole("list", { name: "Enlaces de seguimiento" });
    expect(within(list).getByText("Activo")).toBeInTheDocument();
    expect(list).not.toHaveTextContent(TOKEN);

    await userEvent.click(within(list).getByRole("button", { name: "Revocar enlace" }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith("/link-1/revoke"))).toBe(true));
    const revoke = fetchMock.mock.calls.find(([u]) => String(u).endsWith("/link-1/revoke"))!;
    expect(JSON.parse(String((revoke[1] as RequestInit).body))).toEqual({
      reason: "enviado al cliente equivocado", version: 0,
    });
    expect(await screen.findByText("Revocado")).toBeInTheDocument();
  });

  it("a dismissed reason revokes nothing", async () => {
    links = [ACTIVE];
    promptAnswer.value = null;
    renderPanel();

    await userEvent.click(await screen.findByRole("button", { name: "Revocar enlace" }));
    expect(fetchMock.mock.calls.some(([u]) => String(u).includes("/revoke"))).toBe(false);
  });

  it("is only offered to someone who may read the order and manage its links", () => {
    permissions = ["orders.order:read"];
    expect(renderHook(() => useCanShareTracking()).result.current).toBe(false);
    permissions = ["orders.tracking_link:manage"];
    expect(renderHook(() => useCanShareTracking()).result.current).toBe(false);
    permissions = ["orders.order:read", "orders.tracking_link:manage"];
    expect(renderHook(() => useCanShareTracking()).result.current).toBe(true);
  });

  it("the message works without a company name", () => {
    expect(trackingMessage("TO-1", null, "https://x/t/abc")).toBe("Hola. Puede seguir su pedido TO-1 en este enlace: https://x/t/abc");
  });
});
