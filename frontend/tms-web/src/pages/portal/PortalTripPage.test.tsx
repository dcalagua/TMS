import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PortalTripDetail } from "../../shared/api/portalApi";
import { emulatePhoneViewport } from "../../test/phoneViewport";
import { createMemoryStore } from "./offline/memoryStore";
import { PortalOfflineProvider } from "./offline/PortalOfflineProvider";
import type { DocumentOutboxEntry as OutboxEntry, ExecutionOutboxEntry } from "./offline/types";

/**
 * La pantalla de un viaje en el teléfono (ADR-019 §1): la cola reflejada sobre los documentos,
 * la navegación a cada parada y la copia guardada cuando no hay señal.
 */

const COMPANY = "11111111-1111-4111-8111-111111111111";
const TRIP = "t-1";

/** Los permisos del usuario del portal; cada prueba puede quitarle los de V70. */
let permissions: string[] = ["portal.trip:read", "portal.document:report", "portal.stop:execute", "portal.delivery:report"];

vi.mock("../../shared/company/CompanyContext", () => ({
  CompanyProvider: ({ children }: { children: ReactNode }) => children,
  useCompany: () => ({ selected: { id: COMPANY }, hasPermission: (p: string) => permissions.includes(p) }),
}));

import { PortalTripPage } from "./PortalTripPage";

const DETAIL: PortalTripDetail = {
  trip: {
    tripId: TRIP, shipmentNumber: "SH-0042", status: "DISPATCHED", planningDate: "2026-10-12",
    dispatchedAt: "2026-10-12T12:00:00Z", originName: "CD Lurín", vehicleLicensePlate: "ABC-123", driverName: "Juan",
    stopCount: 2, documentCount: 3, pendingCount: 3, deliveredCount: 0, rejectedCount: 0, notDeliveredCount: 0,
    returnedCount: 0,
  },
  evidenceEnabled: true,
  stops: [
    {
      stopId: "s-1", sequence: 1, destinationName: "Bodega San Martín", address: "Av. Perú 123", latitude: -12.05, longitude: -77.04,
      documents: [
        { id: "dd-1", documentId: "d-1", documentType: "INVOICE", documentNumber: "F001-1", orderNumbers: ["TO-1"],
          customerName: "Bodega San Martín", status: "PENDING", reasonCode: null, remark: null, reportedAt: null,
          recordedAt: null, returnedAt: null, editable: true, evidence: [] },
        { id: "dd-2", documentId: "d-2", documentType: "DELIVERY_NOTE", documentNumber: "G001-2", orderNumbers: ["TO-1"],
          customerName: "Bodega San Martín", status: "PENDING", reasonCode: null, remark: null, reportedAt: null,
          recordedAt: null, returnedAt: null, editable: true, evidence: [] },
      ],
    },
    {
      stopId: "s-2", sequence: 2, destinationName: "Minimarket Lucía", address: "Jr. Lima 45",
      documents: [
        { id: "dd-3", documentId: "d-3", documentType: "INVOICE", documentNumber: "F001-3", orderNumbers: ["TO-2"],
          customerName: "Minimarket Lucía", status: "PENDING", reasonCode: null, remark: null, reportedAt: null,
          recordedAt: null, returnedAt: null, editable: true, evidence: [] },
      ],
    },
  ],
};

const QUEUED: OutboxEntry = {
  id: "e-1", userId: "u-1", companyId: COMPANY, tripId: TRIP, documentId: "d-1", idempotencyKey: "k-1",
  status: "REJECTED", reasonCode: "CUSTOMER_REFUSED", remark: null, reportedAt: "2026-10-12T14:00:00.000Z",
  photos: [], signature: null, createdAt: "2026-10-12T14:00:00.000Z", attempts: 0, lastError: null, state: "PENDING",
  nextAttemptAt: "2099-01-01T00:00:00.000Z", documentType: "INVOICE", documentNumber: "F001-1", customerName: null,
  shipmentNumber: "SH-0042",
};

let store: ReturnType<typeof createMemoryStore>;

function renderTrip() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <PortalOfflineProvider userId="u-1" store={store} send={() => Promise.reject(new TypeError("Failed to fetch"))}>
        <MemoryRouter initialEntries={[`/portal/trips/${TRIP}`]}>
          <Routes>
            <Route path="/portal/trips/:tripId" element={<PortalTripPage />} />
          </Routes>
        </MemoryRouter>
      </PortalOfflineProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  emulatePhoneViewport(390);
  permissions = ["portal.trip:read", "portal.document:report", "portal.stop:execute", "portal.delivery:report"];
  store = createMemoryStore();
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(DETAIL), {
    status: 200, headers: { "content-type": "application/json" },
  })));
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("la pantalla del viaje en el portal", () => {
  it("enseña lo que espera en la cola como ya reportado, marcado «Pendiente de sincronizar»", async () => {
    await store.putOutbox(QUEUED);
    renderTrip();

    const row = await screen.findByRole("button", { name: "Factura F001-1" });
    expect(within(row).getByText("Rechazado")).toBeInTheDocument();
    expect(within(row).getByText("Pendiente de sincronizar")).toBeInTheDocument();
    expect(screen.getByText("1 de 3 reportados · 2 pendientes")).toBeInTheDocument();
  });

  it("«Cómo llegar» abre la navegación por coordenadas, o por dirección si no las hay", async () => {
    renderTrip();

    const links = await screen.findAllByRole("link", { name: /Cómo llegar a/ });
    expect(links[0]).toHaveAttribute("href", "https://www.google.com/maps/dir/?api=1&destination=-12.05,-77.04");
    expect(links[1]).toHaveAttribute("href", `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent("Jr. Lima 45")}`);
  });

  it("el botón «Siguiente pendiente» abre el primer documento por reportar", async () => {
    renderTrip();

    await userEvent.click(await screen.findByRole("button", { name: /Siguiente pendiente/ }));

    expect(await screen.findByRole("heading", { name: "Factura F001-1" })).toBeInTheDocument();
  });

  it("ADR-028: una guía ecuatoriana se nombra SRI 06 y su clave de acceso se copia desde la tarjeta", async () => {
    const clave = "1510202606099299999900110010010000001231234567819";
    const withGuide: PortalTripDetail = {
      ...DETAIL,
      stops: [{
        ...DETAIL.stops[0],
        documents: [{
          id: "dd-9", documentId: "d-9", documentType: "REMISSION_GUIDE", documentNumber: "001-001-000000123",
          orderNumbers: ["TO-1"], customerName: "Ferretería El Constructor", status: "PENDING", reasonCode: null,
          remark: null, reportedAt: null, recordedAt: null, returnedAt: null, editable: true, evidence: [],
          fiscal: { fiscalCountry: "EC", fiscalDocumentCode: "06", authorizationNumber: clave },
        }],
      }],
    };
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(withGuide), {
      status: 200, headers: { "content-type": "application/json" },
    })));
    const user = userEvent.setup();
    renderTrip();

    expect(await screen.findByRole("button", { name: "Guía de remisión (SRI 06) 001-001-000000123" })).toBeInTheDocument();
    expect(screen.getByText(clave)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Copiar clave de acceso" }));
    await expect(navigator.clipboard.readText()).resolves.toBe(clave);
    // Un documento sin datos fiscales no enseña ninguna clave.
    expect(screen.queryAllByRole("button", { name: /^Copiar/ })).toHaveLength(1);
  });

  it("sin señal enseña la copia guardada y de cuándo es", async () => {
    await store.putSnapshot("u-1", COMPANY, `trip:${TRIP}`, DETAIL, new Date().toISOString());
    vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
    renderTrip();

    expect(await screen.findByText(/^Sin conexión · datos de las /)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Factura F001-1" })).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  describe("V70: las paradas y las entregas desde el portal", () => {
    const ON_THE_ROAD: PortalTripDetail = {
      ...DETAIL,
      trip: { ...DETAIL.trip, status: "IN_TRANSIT" },
      incidents: [],
      stops: [
        {
          ...DETAIL.stops[0],
          executionStatus: "PENDING", allowedExecutionTransitions: ["ARRIVED", "SKIPPED", "FAILED"],
          actualArrivalAt: null, serviceStartedAt: null, actualDepartureAt: null,
          orders: [{ orderId: "o-1", orderNumber: "TO-1", customerName: "Bodega San Martín", assignedWeightKg: 1000,
            assignedVolumeM3: 10, assignedPallets: 10, delivery: null }],
        },
        {
          ...DETAIL.stops[1],
          executionStatus: "ARRIVED", allowedExecutionTransitions: ["IN_SERVICE", "COMPLETED", "FAILED"],
          actualArrivalAt: "2026-10-12T15:00:00Z", serviceStartedAt: null, actualDepartureAt: null,
          orders: [{ orderId: "o-2", orderNumber: "TO-2", customerName: "Minimarket Lucía", assignedWeightKg: 500,
            assignedVolumeM3: 5, assignedPallets: 4,
            delivery: { id: "del-2", result: "DELIVERED", deliveredAt: "2026-10-12T15:10:00Z", receiverName: "Lucía",
              receiverDocument: null, notes: null, source: "OPERATOR", recordedAt: "2026-10-12T15:11:00Z",
              evidenceCount: 0, refusedWeightKg: null, refusedVolumeM3: null, refusedPallets: null } }],
        },
      ],
    };

    /** Un servidor con memoria: tras la llegada a la parada 1, también la lectura la trae. */
    function serve(detail: PortalTripDetail) {
      let current = detail;
      vi.stubGlobal("fetch", vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === "POST") {
          current = {
            ...current,
            stops: current.stops.map((stop, i) => i === 0 ? { ...stop, executionStatus: "ARRIVED",
              allowedExecutionTransitions: ["IN_SERVICE", "COMPLETED", "FAILED"] } : stop),
          };
        }
        return new Response(JSON.stringify(current), { status: 200, headers: { "content-type": "application/json" } });
      }));
    }

    it("ofrece solo lo que la parada permite, y nunca «omitir», que es cosa de la oficina", async () => {
      serve(ON_THE_ROAD);
      renderTrip();

      const first = await screen.findByRole("listitem", { name: "Bodega San Martín" });
      expect(within(first).getByText("Sin iniciar")).toBeInTheDocument();
      expect(within(first).getByRole("button", { name: "Llegué" })).toBeInTheDocument();
      expect(within(first).getByRole("button", { name: "No se pudo atender" })).toBeInTheDocument();
      expect(within(first).queryByRole("button", { name: /Omit/ })).not.toBeInTheDocument();
      // Sin llegar a la parada no se registra una entrega: se dice por qué.
      expect(within(first).queryByRole("button", { name: /Registrar entrega/ })).not.toBeInTheDocument();
      expect(within(first).getByText("Marca «Llegué» para registrar la entrega de los pedidos.")).toBeInTheDocument();

      const second = screen.getByRole("listitem", { name: "Minimarket Lucía" });
      expect(within(second).getByRole("button", { name: "Empecé la descarga" })).toBeInTheDocument();
      expect(within(second).getByRole("button", { name: "Terminé la parada" })).toBeInTheDocument();
      expect(within(second).getByText("Registrado por la oficina")).toBeInTheDocument();
      expect(within(second).getByRole("button", { name: "Corregir entrega TO-2" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Reportar incidencia del viaje" })).toBeInTheDocument();
    });

    it("«Llegué» envía la llegada con su clave y la hora del toque", async () => {
      serve(ON_THE_ROAD);
      const user = userEvent.setup();
      renderTrip();

      const first = await screen.findByRole("listitem", { name: "Bodega San Martín" });
      await user.click(within(first).getByRole("button", { name: "Llegué" }));

      const calls = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls as [string, RequestInit][];
      const post = await waitForPost(calls);
      expect(post[0]).toContain(`/portal/trips/${TRIP}/stops/s-1/arrive`);
      expect((post[1].headers as Record<string, string>)["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/);
      expect(JSON.parse(post[1].body as string).occurredAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
      expect(await within(first).findByText("En el punto")).toBeInTheDocument();
    });

    it("sin los permisos de V70 la parada se ve, pero no ofrece ninguna acción", async () => {
      permissions = ["portal.trip:read", "portal.document:report"];
      serve(ON_THE_ROAD);
      renderTrip();

      const first = await screen.findByRole("listitem", { name: "Bodega San Martín" });
      expect(within(first).getByText("Sin iniciar")).toBeInTheDocument();
      expect(within(first).queryByRole("button", { name: "Llegué" })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Reportar incidencia del viaje" })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /Corregir entrega/ })).not.toBeInTheDocument();
    });

    it("una llegada que espera en la cola ya se enseña, marcada «Pendiente de sincronizar»", async () => {
      const queued: ExecutionOutboxEntry = {
        id: "x-1", userId: "u-1", companyId: COMPANY, tripId: TRIP, kind: "EXECUTION", idempotencyKey: "k-x",
        action: { type: "STOP", stopId: "s-1", transition: "arrive", occurredAt: "2026-10-12T14:30:00.000Z" },
        title: "Llegué · Parada 1", reportedAt: "2026-10-12T14:30:00.000Z", createdAt: "2026-10-12T14:30:00.000Z",
        attempts: 0, lastError: null, state: "PENDING", nextAttemptAt: "2099-01-01T00:00:00.000Z", shipmentNumber: "SH-0042",
      };
      await store.putOutbox(queued);
      serve(ON_THE_ROAD);
      renderTrip();

      const first = await screen.findByRole("listitem", { name: "Bodega San Martín" });
      expect(within(first).getByText("En el punto")).toBeInTheDocument();
      expect(within(first).getAllByText("Pendiente de sincronizar").length).toBeGreaterThan(0);
      // Ya en el punto: ahora sí se registra la entrega del pedido.
      expect(within(first).getByRole("button", { name: "Registrar entrega TO-1" })).toBeInTheDocument();
    });

    it("un viaje que ya no está en ruta no ofrece acciones: las correcciones son de la oficina", async () => {
      serve({ ...ON_THE_ROAD, trip: { ...ON_THE_ROAD.trip, status: "COMPLETED" },
        stops: ON_THE_ROAD.stops.map((stop) => ({ ...stop, allowedExecutionTransitions: [] })) });
      renderTrip();

      const second = await screen.findByRole("listitem", { name: "Minimarket Lucía" });
      expect(within(second).queryByRole("button", { name: /Terminé|Corregir/ })).not.toBeInTheDocument();
    });
  });
});

async function waitForPost(calls: [string, RequestInit][]): Promise<[string, RequestInit]> {
  for (let i = 0; i < 50; i++) {
    const found = calls.find(([, init]) => init?.method === "POST");
    if (found) return found;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error("no POST was sent");
}
