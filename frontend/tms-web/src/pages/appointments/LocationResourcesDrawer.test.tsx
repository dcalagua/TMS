import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { LocationResourceView } from "../../shared/api/appointmentsApi";

/**
 * Puertas y muelles de un sitio (V41), con la API simulada en `fetch`.
 *
 * Lo que se protege: que crear una puerta mande lo que el backend valida; que ponerla fuera de
 * servicio pase por `confirmDialog` (y no se haga si se cancela); que el horario mande la semana
 * entera de una vez; y que la pantalla avise que una semana sin días deja la puerta abierta
 * siempre, no cerrada.
 */

const confirmDialog = vi.fn(async () => true);
vi.mock("../../lib/ui", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/ui")>()),
  confirmDialog: (...args: unknown[]) => confirmDialog(...(args as [])),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));

import { LocationResourcesDrawer } from "./LocationResourcesDrawer";

const SITE = { id: "site-1", code: "GYE-CD", name: "Planta Guayaquil", timeZone: "America/Guayaquil" };

function dock(overrides: Partial<LocationResourceView> = {}): LocationResourceView {
  return {
    id: "dock-1", locationId: "site-1", code: "MUE-01", name: "Muelle 1", resourceType: "DOCK",
    defaultSlotMinutes: 60, active: true,
    openingHours: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"].map((day) => ({
      day: day as LocationResourceView["openingHours"][number]["day"], opensAt: "07:00:00", closesAt: "19:00:00",
    })),
    createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z",
    ...overrides,
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

let served: LocationResourceView[];
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  served = [dock(), dock({ id: "dock-2", code: "MUE-02", name: "Muelle 2", openingHours: [] })];
  confirmDialog.mockReset();
  confirmDialog.mockResolvedValue(true);
  fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input);
    const method = init?.method ?? "GET";
    if (method === "GET" && url.pathname.endsWith("/appointments/resources")) return json(served);
    if (method === "GET" && url.pathname.endsWith("/blocks")) return json([]);
    if (method === "POST" && url.pathname.endsWith("/appointments/resources")) {
      return json(dock({ id: "dock-3", ...JSON.parse(init?.body as string), openingHours: [] }), 201);
    }
    if (method === "POST" && url.pathname.endsWith("/deactivate")) return json(dock({ active: false }));
    if (method === "PUT" && url.pathname.endsWith("/calendar")) return json(dock());
    return new Response("{}", { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderDrawer() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <LocationResourcesDrawer companyId="company-1" site={SITE} onClose={() => {}} />
    </QueryClientProvider>,
  );
}

function calls(method: string, suffix: string) {
  return fetchMock.mock.calls.filter(([input, init]) =>
    ((init as RequestInit | undefined)?.method ?? "GET") === method && new URL(input as string).pathname.endsWith(suffix));
}

describe("las puertas y muelles de un sitio", () => {
  it("lista las puertas con su horario resumido, y la que no tiene horario dice que está abierta siempre", async () => {
    renderDrawer();

    expect(await screen.findByText("MUE-01")).toBeInTheDocument();
    expect(screen.getByText("Lun–Sáb 07:00–19:00")).toBeInTheDocument();
    expect(screen.getByText("Sin horario: abierta siempre")).toBeInTheDocument();
    expect(screen.getByText("GYE-CD · Planta Guayaquil")).toBeInTheDocument();
  });

  it("crea una puerta con el código en mayúsculas y la duración por defecto", async () => {
    const user = userEvent.setup();
    renderDrawer();
    await screen.findByText("MUE-01");

    await user.click(screen.getByRole("button", { name: "Nueva puerta" }));
    await user.type(screen.getByLabelText(/Código/), "mue-03");
    await user.type(screen.getByLabelText(/^Nombre/), "Muelle 3");
    await user.click(screen.getByRole("button", { name: "Crear puerta" }));

    await waitFor(() => expect(calls("POST", "/appointments/resources")).toHaveLength(1));
    const body = JSON.parse((calls("POST", "/appointments/resources")[0][1] as RequestInit).body as string);
    expect(body).toEqual({
      locationId: "site-1", code: "MUE-03", name: "Muelle 3", resourceType: "DOCK", defaultSlotMinutes: 60,
    });
  });

  it("no deja crear con un código que el backend rechazaría", async () => {
    const user = userEvent.setup();
    renderDrawer();
    await screen.findByText("MUE-01");

    await user.click(screen.getByRole("button", { name: "Nueva puerta" }));
    await user.type(screen.getByLabelText(/Código/), "MUE 03");
    await user.type(screen.getByLabelText(/^Nombre/), "Muelle 3");

    expect(screen.getByText(/letras, números, guion o guion bajo/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear puerta" })).toBeDisabled();
  });

  it("pone una puerta fuera de servicio sólo después de confirmar", async () => {
    const user = userEvent.setup();
    renderDrawer();
    await screen.findByText("MUE-01");

    confirmDialog.mockResolvedValueOnce(false);
    await user.click(screen.getByRole("button", { name: "Acciones de MUE-01" }));
    await user.click(await screen.findByRole("menuitem", { name: "Poner fuera de servicio" }));
    await waitFor(() => expect(confirmDialog).toHaveBeenCalledTimes(1));
    expect(calls("POST", "/deactivate")).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "Acciones de MUE-01" }));
    await user.click(await screen.findByRole("menuitem", { name: "Poner fuera de servicio" }));
    await waitFor(() => expect(calls("POST", "/dock-1/deactivate")).toHaveLength(1));
  });

  it("guarda la semana entera de una vez, y avisa que sin días la puerta queda abierta siempre", async () => {
    const user = userEvent.setup();
    renderDrawer();
    await screen.findByText("MUE-02");

    await user.click(screen.getByRole("button", { name: "Acciones de MUE-02" }));
    await user.click(await screen.findByRole("menuitem", { name: "Horario semanal" }));

    expect(screen.getByText(/se considera abierta siempre/)).toBeInTheDocument();
    for (const day of ["Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado"]) {
      await user.click(screen.getByRole("checkbox", { name: day }));
    }
    expect(screen.queryByText(/se considera abierta siempre/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Guardar horario" }));

    await waitFor(() => expect(calls("PUT", "/dock-2/calendar")).toHaveLength(1));
    const body = JSON.parse((calls("PUT", "/dock-2/calendar")[0][1] as RequestInit).body as string);
    expect(body.days).toHaveLength(6);
    expect(body.days[0]).toEqual({ day: "MONDAY", opensAt: "07:00", closesAt: "19:00" });
    expect(body.days.map((d: { day: string }) => d.day)).not.toContain("SUNDAY");
  });

  it("los cierres piden motivo y dicen que las reservas existentes no se cancelan solas", async () => {
    const user = userEvent.setup();
    renderDrawer();
    await screen.findByText("MUE-01");

    await user.click(screen.getByRole("button", { name: "Acciones de MUE-01" }));
    await user.click(await screen.findByRole("menuitem", { name: "Cierres puntuales" }));

    expect(await screen.findByText("Esta puerta no tiene cierres por delante.")).toBeInTheDocument();
    expect(screen.getByText(/no se cancelan solas/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Registrar cierre" })).toBeDisabled();
    const back = screen.getByRole("button", { name: "Volver a las puertas" });
    await user.click(back);
    expect(within(screen.getByRole("table")).getByText("MUE-01")).toBeInTheDocument();
  });
});
