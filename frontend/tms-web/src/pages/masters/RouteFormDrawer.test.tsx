import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const ui = vi.hoisted(() => ({ notifyError: vi.fn() }));
vi.mock("../../lib/ui", async (original) => ({ ...(await original<typeof import("../../lib/ui")>()), notifyError: ui.notifyError }));

import { RouteFormDrawer } from "./RouteFormDrawer";

/**
 * «Optimizar paradas» (ADR-030) en una ruta que todavía no existe: el botón se habilita en cuanto
 * hay un origen y dos paradas en el formulario, sin haber guardado nada.
 */

function location(id: string, code: string, name: string) {
  return {
    id, code, name, latitude: -12.05, longitude: -77.04, serviceTimeMinutes: 0, active: true, roles: [],
  };
}

const ORIGINS = [location("origin-1", "CD-LURIN", "Planta Lurín")];
const DESTINATIONS = [location("dest-1", "BOD-1", "Bodega Uno"), location("dest-2", "BOD-2", "Bodega Dos")];

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const content = url.includes("role=ORIGIN") || url.includes("/origins") ? ORIGINS
      : url.includes("role=DESTINATION") || url.includes("/destinations") ? DESTINATIONS
        : [];
    return new Response(JSON.stringify({ content, page: 0, size: 200, totalElements: content.length }), {
      status: 200, headers: { "content-type": "application/json" },
    });
  }));
});

afterEach(() => {
  vi.unstubAllGlobals();
  ui.notifyError.mockReset();
});

function renderNewRoute() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouteFormDrawer companyId="company-1" routeId={null} onClose={vi.fn()} onSaved={vi.fn()} />
    </QueryClientProvider>,
  );
}

async function addStops(user: ReturnType<typeof userEvent.setup>) {
  await choose(user, /Destino a agregar/, /BOD-1/);
  await user.click(screen.getByRole("button", { name: "Agregar" }));
  await choose(user, /Destino a agregar/, /BOD-2/);
  await user.click(screen.getByRole("button", { name: "Agregar" }));
}

async function choose(user: ReturnType<typeof userEvent.setup>, label: RegExp, option: RegExp) {
  await user.click(screen.getByRole("combobox", { name: label }));
  await user.click(within(await screen.findByRole("listbox")).getByRole("option", { name: option }));
}

describe("optimizar paradas al crear una ruta", () => {
  it("se habilita con origen y dos paradas, sin haber guardado la ruta", async () => {
    const user = userEvent.setup();
    renderNewRoute();

    const optimize = await screen.findByRole("button", { name: /Optimizar paradas/ });
    expect(optimize).toBeDisabled();

    await choose(user, /Origen/, /CD-LURIN/);
    await addStops(user);

    expect(screen.getByText("Bodega Uno")).toBeInTheDocument();
    expect(screen.getByText("Bodega Dos")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Optimizar paradas/ })).toBeEnabled();
  });

  it("las paradas se ven como lista o como mapa, y el mapa sigue el orden de la lista", async () => {
    const user = userEvent.setup();
    renderNewRoute();
    await screen.findByRole("button", { name: /Optimizar paradas/ });
    await choose(user, /Origen/, /CD-LURIN/);
    await addStops(user);
    expect(screen.getAllByLabelText(/Servicio \(min\)/)).toHaveLength(2);

    await user.click(screen.getByRole("button", { name: "Mapa" }));

    expect(screen.getByRole("application", { name: "Mapa de paradas" })).toBeInTheDocument();
    expect(screen.getByText(/El origen es el 0 y las paradas van numeradas/)).toBeInTheDocument();
    // En el mapa no se editan las paradas: los campos son de la lista.
    expect(screen.queryAllByLabelText(/Servicio \(min\)/)).toHaveLength(0);
    // Agregar paradas sigue a mano, con el mapa a la vista.
    expect(screen.getByRole("combobox", { name: /Destino a agregar/ })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Lista" }));
    expect(screen.getAllByLabelText(/Servicio \(min\)/)).toHaveLength(2);
  });

  it("con dos paradas y sin origen se deja pulsar, y pide el origen sin llamar al asistente", async () => {
    const user = userEvent.setup();
    renderNewRoute();
    await screen.findByRole("button", { name: /Optimizar paradas/ });

    await addStops(user);
    const optimize = screen.getByRole("button", { name: /Optimizar paradas/ });
    expect(optimize).toBeEnabled();

    await user.click(optimize);

    expect(ui.notifyError).toHaveBeenCalledWith("Falta el origen", expect.stringContaining("elígelo"));
    const calls = vi.mocked(fetch).mock.calls.map(([input]) => String(input));
    expect(calls.some((url) => url.includes("optimize-stops"))).toBe(false);
  });
});
