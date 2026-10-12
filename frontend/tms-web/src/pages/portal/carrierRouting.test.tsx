import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { CompanyAccessView } from "../../shared/api/meApi";

/**
 * Quién va a dónde (ADR-018 §7).
 *
 * Un usuario cuya empresa elegida es de transportista entra al portal y nunca ve la oficina, pida
 * la ruta que pida; uno de oficina sin la capability del portal no lo ve. Es UX y no seguridad
 * —los roles CARRIER no tienen ningún permiso de oficina—, pero un transportista que aterriza en
 * un tablero de oficina lleno de 403 lee "la aplicación no funciona".
 */

const company = (overrides: Partial<CompanyAccessView>): CompanyAccessView => ({
  id: "11111111-1111-4111-8111-111111111111",
  code: "DEMO",
  name: "Demo Lima",
  timeZone: "America/Lima",
  organization: { id: "o-1", code: "ORG", name: "Org" },
  permissions: [],
  capabilities: [],
  carrierId: null,
  carrierName: null,
  ...overrides,
});

const CARRIER_COMPANY = company({
  capabilities: ["CARRIER_PORTAL"],
  carrierId: "22222222-2222-4222-8222-222222222222",
  carrierName: "Transportes Andinos SAC",
});
const OFFICE_COMPANY = company({ capabilities: ["TRIPS_VIEW", "DOCUMENT_DELIVERY_VIEW"] });

let current: CompanyAccessView;

vi.mock("../../shared/auth/AuthContext", () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({ status: "signedIn", user: { id: "u-1", email: "chofer@andinos.pe" }, signOut: vi.fn() }),
}));

vi.mock("../../shared/company/CompanyContext", () => ({
  CompanyProvider: ({ children }: { children: ReactNode }) => children,
  useCompany: () => ({
    status: "ready",
    profile: { id: "u-1", email: "chofer@andinos.pe", fullName: "Juan Quispe" },
    companies: [current],
    selected: current,
    selectCompany: vi.fn(),
    hasPermission: (p: string) => current.permissions.includes(p),
    hasCapability: (c: string) => current.capabilities.includes(c),
    errorMessage: null,
    refetch: vi.fn(),
  }),
}));

import App from "../../App";
import { OfficeOnly } from "../../shared/company/OfficeOnly";
import { PortalLayout } from "./PortalLayout";

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>;
}

/** La misma forma que la tabla de `App`, con pantallas de mentira para aislar las guardas. El
 * portal lleva su cola sin señal, que necesita el cliente de consultas como en la app real. */
function renderGuards(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="portal" element={<PortalLayout />}>
          <Route index element={<div>PORTAL</div>} />
        </Route>
        <Route element={<OfficeOnly />}>
          <Route index element={<div>OFICINA</div>} />
          <Route path="orders" element={<div>PEDIDOS</div>} />
        </Route>
      </Routes>
      <Where />
    </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response("[]", { status: 200, headers: { "content-type": "application/json" } })));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("el enrutado de un usuario de transportista", () => {
  it("al entrar por `/` aterriza en el portal de la app real, sin menú de oficina", async () => {
    current = CARRIER_COMPANY;
    render(
      <MemoryRouter initialEntries={["/"]}>
        <App />
        <Where />
      </MemoryRouter>,
    );

    expect(await screen.findByRole("heading", { name: "Mis viajes" })).toBeInTheDocument();
    expect(screen.getByTestId("where")).toHaveTextContent("/portal");
    expect(screen.getByText("Transportes Andinos SAC")).toBeInTheDocument();
    // Nada del armazón de oficina.
    expect(screen.queryByRole("navigation", { name: "Ubicación" })).not.toBeInTheDocument();
    expect(screen.queryByText("Planificación")).not.toBeInTheDocument();
  });

  it("una ruta de oficina pedida a mano también lo devuelve al portal", () => {
    current = CARRIER_COMPANY;
    renderGuards("/orders");

    expect(screen.getByText("PORTAL")).toBeInTheDocument();
    expect(screen.getByTestId("where")).toHaveTextContent("/portal");
  });

  it("sin la capability del portal ve un aviso, no un bucle de redirecciones", () => {
    current = { ...CARRIER_COMPANY, capabilities: [] };
    renderGuards("/");

    expect(screen.getByText("Tu acceso no incluye el portal")).toBeInTheDocument();
    expect(screen.queryByText("PORTAL")).not.toBeInTheDocument();
  });
});

describe("el enrutado de un usuario de oficina", () => {
  it("entra a la oficina por `/`", () => {
    current = OFFICE_COMPANY;
    renderGuards("/");

    expect(screen.getByText("OFICINA")).toBeInTheDocument();
  });

  it("sin CARRIER_PORTAL no ve el portal: vuelve a `/`", () => {
    current = OFFICE_COMPANY;
    renderGuards("/portal");

    expect(screen.getByText("OFICINA")).toBeInTheDocument();
    expect(screen.getByTestId("where")).toHaveTextContent(/^\/$/);
  });
});
