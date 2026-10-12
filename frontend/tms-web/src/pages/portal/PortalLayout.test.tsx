import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UiHost } from "../../lib/ui";
import type { CompanyAccessView } from "../../shared/api/meApi";
import { readMeCopy, writeMeCopy } from "../../shared/company/meOfflineCopy";
import { createMemoryStore } from "./offline/memoryStore";
import type { OutboxEntry } from "./offline/types";

/**
 * El armazón del portal y lo que guarda el teléfono (ADR-019 §1): al cerrar sesión se avisa de lo
 * que queda sin enviar y se borra lo de ese usuario; al entrar otro usuario, se borra lo del
 * anterior. Un teléfono compartido no enseña a nadie lo que reportó otro.
 */

const store = createMemoryStore();
const signOut = vi.fn(async () => undefined);
let currentUser = "u-1";

vi.mock("./offline/defaultStore", () => ({ defaultOfflineStore: () => store }));

vi.mock("../../shared/auth/AuthContext", () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({ status: "signedIn", user: { id: currentUser, email: "chofer@andinos.pe" }, signOut }),
}));

const COMPANY: CompanyAccessView = {
  id: "11111111-1111-4111-8111-111111111111",
  code: "DEMO",
  name: "Demo Lima",
  timeZone: "America/Lima",
  organization: { id: "o-1", code: "ORG", name: "Org" },
  permissions: [],
  capabilities: ["CARRIER_PORTAL"],
  carrierId: "22222222-2222-4222-8222-222222222222",
  carrierName: "Transportes Andinos SAC",
};

vi.mock("../../shared/company/CompanyContext", () => ({
  CompanyProvider: ({ children }: { children: ReactNode }) => children,
  useCompany: () => ({
    status: "ready",
    profile: { id: currentUser, email: "chofer@andinos.pe", fullName: "Juan Quispe" },
    companies: [COMPANY],
    selected: COMPANY,
    selectCompany: vi.fn(),
    hasPermission: () => false,
    hasCapability: (c: string) => COMPANY.capabilities.includes(c),
    errorMessage: null,
    refetch: vi.fn(),
  }),
}));

import { PortalLayout } from "./PortalLayout";

function entry(overrides: Partial<OutboxEntry> = {}): OutboxEntry {
  return {
    id: "e-1", userId: "u-1", companyId: COMPANY.id, tripId: "t-1", documentId: "d-1", idempotencyKey: "k-1",
    status: "NOT_DELIVERED", reasonCode: "CUSTOMER_ABSENT", remark: null, reportedAt: "2026-10-12T14:00:00.000Z",
    photos: [], signature: null, createdAt: "2026-10-12T14:00:00.000Z", attempts: 0, lastError: null,
    state: "PENDING", nextAttemptAt: "2099-01-01T00:00:00.000Z", documentType: "INVOICE",
    documentNumber: "F001-000123", customerName: "Bodega", shipmentNumber: "SH-1", ...overrides,
  };
}

function renderPortal() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={["/portal"]}>
        <UiHost />
        <Routes>
          <Route path="portal" element={<PortalLayout />}>
            <Route index element={<div>LISTA</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  store.snapshots.clear();
  store.outbox.clear();
  currentUser = "u-1";
  signOut.mockClear();
  vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))));
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("el portal y los datos del teléfono", () => {
  it("al cerrar sesión con reportes en cola avisa, y si se confirma borra lo de ese usuario", async () => {
    await store.putOutbox(entry());
    await store.putSnapshot("u-1", COMPANY.id, "trips:14:false", []);
    writeMeCopy("u-1", { user: { id: "u-1", email: "chofer@andinos.pe", fullName: "Juan" } as never, companies: [COMPANY] });
    renderPortal();

    expect(await screen.findByRole("button", { name: "Sincronización: 1 por sincronizar" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Cuenta" }));
    await userEvent.click(screen.getByRole("menuitem", { name: "Cerrar sesión" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("Tienes 1 reporte sin sincronizar; si cierras sesión se perderá.")).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Cerrar sesión y perderlos" }));

    await waitFor(() => expect(signOut).toHaveBeenCalled());
    expect(await store.listOutbox("u-1")).toEqual([]);
    expect(store.snapshots.size).toBe(0);
    expect(readMeCopy("u-1")).toBeUndefined();
  });

  it("si no confirma, la cola sigue ahí", async () => {
    await store.putOutbox(entry());
    renderPortal();
    await screen.findByRole("button", { name: "Sincronización: 1 por sincronizar" });

    await userEvent.click(screen.getByRole("button", { name: "Cuenta" }));
    await userEvent.click(screen.getByRole("menuitem", { name: "Cerrar sesión" }));
    await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Seguir conectado" }));

    expect(signOut).not.toHaveBeenCalled();
    expect(await store.listOutbox("u-1")).toHaveLength(1);
  });

  it("al entrar otro usuario en el mismo teléfono se borra lo del anterior", async () => {
    await store.putOutbox(entry({ id: "ajena", userId: "u-2" }));
    await store.putSnapshot("u-2", COMPANY.id, "trips:14:false", ["de otro"]);
    await store.putOutbox(entry({ id: "propia" }));
    renderPortal();

    await waitFor(() => expect(store.outbox.has("ajena")).toBe(false));
    expect(store.snapshots.size).toBe(0);
    expect(store.outbox.has("propia")).toBe(true);
  });

  it("la hoja de sincronización enseña lo que falló, con la razón del servidor, y deja descartarlo", async () => {
    await store.putOutbox(entry({ state: "FAILED", lastError: "La oficina ya confirmó el cargo de este documento." }));
    renderPortal();

    await userEvent.click(await screen.findByRole("button", { name: "Sincronización: 1 con error" }));
    expect(await screen.findByText(/La oficina ya confirmó el cargo/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Descartar" }));
    await userEvent.click(within(await screen.findByRole("dialog", { name: "¿Descartar este reporte?" })).getByRole("button", { name: "Descartar" }));

    await waitFor(() => expect(store.outbox.size).toBe(0));
    expect(await screen.findByText("Todo sincronizado")).toBeInTheDocument();
  });

  it("sin nada en cola y con red, el indicador dice «En línea»", async () => {
    renderPortal();

    expect(await screen.findByRole("button", { name: "Sincronización: En línea" })).toBeInTheDocument();
    expect(screen.getByText("LISTA")).toBeInTheDocument();
  });
});
