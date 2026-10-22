import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "../../a11y/axe";
import type { PublicTrackingView } from "../../shared/api/trackingLinksApi";
import { TrackingPublicPage } from "./TrackingPublicPage";

/**
 * La página que abre el cliente (ADR-029), en un teléfono de 390 px: qué dice en cada estado, que la
 * petición sale sin sesión, que la página pide no indexarse, y que nada se sale de la columna.
 */

const TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";
const PHONE_WIDTH = 390;

const BASE: PublicTrackingView = {
  companyName: "PLAPASA",
  orderNumber: "SO-2026-10045",
  reference: "PED-778",
  destinationName: "Tienda Norte",
  status: "IN_TRANSIT",
  timeline: [
    { code: "RECEIVED", state: "DONE", at: "2026-10-21T14:00:00Z", outcome: null },
    { code: "PLANNED", state: "DONE", at: "2026-10-22T13:00:00Z", outcome: null },
    { code: "IN_TRANSIT", state: "DONE", at: "2026-10-22T13:10:00Z", outcome: null },
    { code: "ARRIVED", state: "PENDING", at: null, outcome: null },
    { code: "OUTCOME", state: "PENDING", at: null, outcome: null },
  ],
  arrivalWindow: { from: "2026-10-22T15:00:00Z", to: "2026-10-22T16:00:00Z", basis: "ESTIMATE" },
  carrierName: "Transportes Andinos",
  vehiclePlate: "•••-123",
  proofOfDelivery: null,
  timeZone: "America/Lima",
  expiresAt: "2026-11-21T14:00:00Z",
  generatedAt: "2026-10-22T14:30:00Z",
};

const DELIVERED: PublicTrackingView = {
  ...BASE,
  status: "DELIVERED",
  timeline: [
    ...BASE.timeline.slice(0, 3),
    { code: "ARRIVED", state: "DONE", at: "2026-10-22T16:20:00Z", outcome: null },
    { code: "OUTCOME", state: "DONE", at: "2026-10-22T16:32:00Z", outcome: "DELIVERED" },
  ],
  arrivalWindow: null,
  proofOfDelivery: { receiverName: "María L.", deliveredAt: "2026-10-22T16:32:00Z" },
};

let fetchMock: ReturnType<typeof vi.fn>;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

/** Un teléfono: ancho de 390 px y `matchMedia` que contesta según ese ancho, como un navegador real. */
function asPhone() {
  Object.defineProperty(window, "innerWidth", { configurable: true, writable: true, value: PHONE_WIDTH });
  window.matchMedia = ((query: string) => {
    const min = /min-width:\s*(\d+(?:\.\d+)?)px/.exec(query);
    const max = /max-width:\s*(\d+(?:\.\d+)?)px/.exec(query);
    const matches = (!min || PHONE_WIDTH >= Number(min[1])) && (!max || PHONE_WIDTH <= Number(max[1]));
    return {
      matches, media: query, onchange: null,
      addListener: () => {}, removeListener: () => {},
      addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
    } as unknown as MediaQueryList;
  }) as typeof window.matchMedia;
  window.dispatchEvent(new Event("resize"));
}

function renderPage(token = TOKEN) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/t/${token}`]}>
        <Routes>
          <Route path="/t/:token" element={<TrackingPublicPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  asPhone();
  fetchMock = vi.fn(async () => json(BASE));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("TrackingPublicPage at 390 px", () => {
  it("shows the status, the arrival window in the company's zone, the carrier and the masked plate", async () => {
    renderPage();

    expect(await screen.findByRole("heading", { level: 1, name: "En ruta" })).toBeInTheDocument();
    expect(screen.getByText("PLAPASA")).toBeInTheDocument();
    expect(screen.getByText("SO-2026-10045")).toBeInTheDocument();
    expect(screen.getByText(/Tienda Norte/)).toBeInTheDocument();
    // 15:00Z-16:00Z is 10:00-11:00 in Lima.
    expect(screen.getByText(/Llega entre las 10:00 y las 11:00/)).toBeInTheDocument();
    expect(screen.getByText(/Transportes Andinos/)).toBeInTheDocument();
    expect(screen.getByText(/•••-123/)).toBeInTheDocument();

    const journey = screen.getByRole("list", { name: "Recorrido del pedido" });
    const steps = within(journey).getAllByRole("listitem");
    expect(steps).toHaveLength(5);
    expect(steps[2]).toHaveTextContent(/En ruta.*completado/);
    expect(steps[3]).toHaveTextContent(/Llegó a destino.*pendiente/);
  });

  it("asks the backend anonymously: no Authorization, no cookies, no referrer", async () => {
    renderPage();
    await screen.findByRole("heading", { level: 1 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(new RegExp(`/public/tracking/${TOKEN}$`));
    const headers = new Headers(init.headers);
    expect(headers.has("Authorization")).toBe(false);
    expect(headers.has("X-Company-Id")).toBe(false);
    expect(init.credentials).toBe("omit");
    expect(init.referrerPolicy).toBe("no-referrer");
  });

  it("asks search engines not to index it and the browser not to send it as a referrer", async () => {
    renderPage();
    await screen.findByRole("heading", { level: 1 });

    expect(document.head.querySelector('meta[name="robots"]')?.getAttribute("content")).toBe("noindex, nofollow");
    expect(document.head.querySelector('meta[name="referrer"]')?.getAttribute("content")).toBe("no-referrer");
  });

  it("a delivered order says when and to whom, with no arrival window", async () => {
    fetchMock.mockImplementation(async () => json(DELIVERED));
    renderPage();

    expect(await screen.findByRole("heading", { level: 1, name: "Entregado" })).toBeInTheDocument();
    expect(screen.getByText("Recibido a las 11:32 por María L.")).toBeInTheDocument();
    expect(screen.queryByText(/Llega entre/)).not.toBeInTheDocument();
  });

  it("an unknown, revoked or expired link explains itself without saying which", async () => {
    fetchMock.mockImplementation(async () => json({ code: "resource-not-found", status: 404 }, 404));
    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent(/no existe o ya no está disponible/);
    expect(screen.queryByRole("heading", { level: 1 })).not.toBeInTheDocument();
  });

  it("a throttled caller is told to wait", async () => {
    fetchMock.mockImplementation(async () => json({ code: "rate-limited", status: 429 }, 429));
    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent(/Demasiadas consultas/);
  });

  it("is one column no wider than the phone, with the 16 px gutter and no horizontal scroll", async () => {
    const { container } = renderPage();
    await screen.findByRole("heading", { level: 1 });

    const main = container.querySelector("main") as HTMLElement;
    expect(getComputedStyle(main).overflowX).toBe("hidden");
    const column = main.firstElementChild as HTMLElement;
    const style = getComputedStyle(column);
    expect(style.width).toBe("100%");
    expect(style.maxWidth).toBe("480px");
    expect(style.paddingLeft).toBe("16px");
    expect(style.paddingRight).toBe("16px");
    // Nothing inside declares a fixed width larger than the phone.
    for (const element of Array.from(container.querySelectorAll<HTMLElement>("*"))) {
      const width = Number.parseFloat(getComputedStyle(element).width);
      if (!Number.isNaN(width) && getComputedStyle(element).width.endsWith("px")) {
        expect(width).toBeLessThanOrEqual(PHONE_WIDTH);
      }
    }
  });

  it("offers Spanish and English", async () => {
    renderPage();
    await screen.findByRole("heading", { level: 1 });

    const languages = screen.getByRole("group", { name: "Idioma" });
    expect(within(languages).getByRole("button", { name: "ES" })).toHaveAttribute("aria-pressed", "true");
    expect(within(languages).getByRole("button", { name: "EN" })).toHaveAttribute("aria-pressed", "false");
  });

  it("has no automatically detectable accessibility violations", async () => {
    const { container } = renderPage();
    await screen.findByRole("heading", { level: 1 });
    expect(await axe(container)).toHaveNoViolations();
  });
});
