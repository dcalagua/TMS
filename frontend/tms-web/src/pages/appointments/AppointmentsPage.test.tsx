import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * El tablero de citas de muelle (V41) con el selector de sitio y la entrada a «Puertas y muelles».
 *
 * Lo que se protege, que es el defecto D6 de la evaluación: el selector lista los ORÍGENES (una
 * planta como GYE-CD) y no sólo los destinos; un sitio sin puertas lo dice en vez de enseñar un
 * día vacío; y configurar puertas es de quien tiene `appointments.resource:manage` — un
 * planificador reserva, pero no ve «Puertas y muelles».
 */

let permissions: string[] = [];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "QAS-PLAPASA", name: "PLAPASA (demo)" },
    hasCapability: () => true,
    hasPermission: (p: string) => permissions.includes(p),
  }),
}));

import { AppointmentsPage } from "./AppointmentsPage";

const LOCATIONS = {
  content: [
    { id: "loc-cli", code: "CLI-SUP-01", name: "Supermercados Costa Azul", roles: ["DESTINATION"], timeZone: "America/Guayaquil" },
    { id: "loc-plant", code: "GYE-CD", name: "Planta Guayaquil", roles: ["ORIGIN"], timeZone: "America/Guayaquil" },
  ],
  page: 0, size: 200, totalElements: 2, totalPages: 1,
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn(async (input: string) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/masterdata/locations")) return json(LOCATIONS);
    if (url.pathname.endsWith("/appointments/resources")) return json([]);
    if (url.pathname.endsWith("/appointments")) return json([]);
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
      <AppointmentsPage />
    </QueryClientProvider>,
  );
}

async function chooseThePlant() {
  const user = userEvent.setup();
  await waitFor(() => expect(fetchMock).toHaveBeenCalled());
  await user.click(screen.getByRole("button", { name: /Sitio/ }));
  const options = await screen.findAllByRole("menuitem");
  // Orígenes primero: la planta encabeza la lista aunque su código ordene después.
  expect(options[0]).toHaveTextContent("GYE-CD · Planta Guayaquil (Origen)");
  await user.click(options[0]);
  return user;
}

describe("el tablero de citas de muelle", () => {
  it("lee el selector de todas las ubicaciones, no sólo de los destinos", async () => {
    permissions = ["appointments.appointment:read"];
    renderPage();
    await chooseThePlant();

    const paths = fetchMock.mock.calls.map(([input]) => new URL(input as string).pathname);
    expect(paths.some((p) => p.endsWith("/masterdata/locations"))).toBe(true);
    expect(paths.some((p) => p.endsWith("/masterdata/destinations"))).toBe(false);
  });

  it("un administrador ve «Puertas y muelles» y, en un sitio sin puertas, cómo configurarlas", async () => {
    permissions = ["appointments.appointment:read", "appointments.appointment:manage", "appointments.resource:manage"];
    renderPage();
    await chooseThePlant();

    expect(await screen.findByText("Este sitio no tiene puertas")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Puertas y muelles" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Configurar puertas" })).toBeInTheDocument();
  });

  it("un planificador reserva pero no configura puertas", async () => {
    permissions = ["appointments.appointment:read", "appointments.appointment:manage"];
    renderPage();
    await chooseThePlant();

    expect(await screen.findByText("Pide a un administrador que configure las puertas de este sitio.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reservar" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Puertas y muelles" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Configurar puertas" })).not.toBeInTheDocument();
  });
});
