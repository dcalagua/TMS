import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AdministeredUserView, RoleView } from "../../shared/api/administrationApi";
import { roleDescription, roleLabel } from "../../lib/enums";

/**
 * D3 (QAS 2026-10-22): «Usuarios y accesos» mostraba los roles con el nombre de `tms.role`, en
 * inglés («Organization administrator», «Planner»…). Se presentan por su código, en español, y un
 * rol que esta versión no conoce cae al nombre del servidor.
 */

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "DEMO", name: "Demo" },
    hasCapability: () => true,
    hasPermission: () => true,
  }),
}));

import { UsersPage } from "./UsersPage";

const role = (code: string, name: string): RoleView => ({
  code, name, description: `${name} (server)`, scopeLevel: "COMPANY", assignable: true, permissionCodes: [],
});

const ROLES: RoleView[] = [
  role("ORGANIZATION_ADMIN", "Organization administrator"),
  role("COMPANY_ADMIN", "Company administrator"),
  role("PLANNER", "Planner"),
  role("VIEWER", "Viewer"),
  { ...role("CARRIER_OPERATOR", "Carrier operator"), scopeLevel: "CARRIER" },
  role("CUSTOM_AUDITOR", "Auditor externo"),
];

const USER: AdministeredUserView = {
  membershipId: "m-1", appUserId: "u-1", email: "ana@example.invalid", fullName: "Ana Torres",
  userActive: true, membershipActive: true, organizationWide: false,
  roleCodes: ["COMPANY_ADMIN", "PLANNER", "VIEWER", "CUSTOM_AUDITOR"], createdAt: "2026-10-01T00:00:00Z",
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
}

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async (input: string) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/admin/users/roles")) return json(ROLES);
    if (url.pathname.endsWith("/admin/users")) return json({ content: [USER], page: 0, size: 25, totalElements: 1 });
    return json({ content: [], page: 0, size: 200, totalElements: 0 });
  }));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("los roles en «Usuarios y accesos»", () => {
  it("la tabla los nombra en español; uno desconocido, con el nombre del servidor", async () => {
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <UsersPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const row = (await screen.findByText("Ana Torres")).closest("tr") as HTMLElement;
    expect(await within(row).findByText("Administrador de la empresa")).toBeInTheDocument();
    expect(within(row).getByText("Planificador")).toBeInTheDocument();
    expect(within(row).getByText("Consulta")).toBeInTheDocument();
    expect(within(row).getByText("Auditor externo")).toBeInTheDocument();
    expect(screen.queryByText(/administrator|Planner|Viewer/)).not.toBeInTheDocument();
  });

  it("roleLabel y roleDescription cubren los cinco roles del catálogo", () => {
    expect(roleLabel("ORGANIZATION_ADMIN", "Organization administrator")).toBe("Administrador de la organización");
    expect(roleLabel("CARRIER_OPERATOR", "Carrier operator")).toBe("Operador del transportista");
    expect(roleLabel("UNKNOWN_ROLE")).toBe("UNKNOWN_ROLE");
    expect(roleDescription("PLANNER", "Reads master data")).toMatch(/^Lee datos maestros/);
    expect(roleDescription("UNKNOWN_ROLE", "Server text")).toBe("Server text");
  });
});

/** ADR-031: quien no tiene cuenta de acceso se ve así, y se le puede reenviar la invitación. */
describe("la invitación en «Usuarios y accesos»", () => {
  it("marca a quien no tiene cuenta y reenvía su invitación", async () => {
    const pending: AdministeredUserView = { ...USER, membershipId: "m-2", email: "nuevo@example.invalid",
      fullName: "Nuevo Usuario", accountLinked: false };
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
      const url = new URL(input);
      if (url.pathname.endsWith("/resend-invitation") && init?.method === "POST") return new Response(null, { status: 202 });
      if (url.pathname.endsWith("/admin/users/roles")) return json(ROLES);
      if (url.pathname.endsWith("/admin/users")) return json({ content: [pending], page: 0, size: 25, totalElements: 1 });
      return json({ content: [], page: 0, size: 200, totalElements: 0 });
    });
    vi.stubGlobal("fetch", fetchMock);
    const user = (await import("@testing-library/user-event")).default.setup();

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <UsersPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const row = (await screen.findByText("Nuevo Usuario")).closest("tr") as HTMLElement;
    expect(within(row).getByText("Sin cuenta de acceso")).toBeInTheDocument();

    await user.click(within(row).getByRole("button", { name: "Abrir menú de acciones" }));
    await user.click(await screen.findByRole("menuitem", { name: /Reenviar invitación/ }));

    expect(fetchMock.mock.calls.some(([input, init]) =>
      String(input).endsWith("/admin/users/m-2/resend-invitation") && (init as RequestInit | undefined)?.method === "POST"))
      .toBe(true);
  });
});
