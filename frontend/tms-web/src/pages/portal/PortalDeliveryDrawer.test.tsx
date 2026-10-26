import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { emulatePhoneViewport } from "../../test/phoneViewport";
import { createMemoryStore } from "./offline/memoryStore";
import type { ShownOrder } from "./offline/overlay";
import { PortalOfflineProvider } from "./offline/PortalOfflineProvider";
import type { ExecutionOutboxEntry, OutboxEntry } from "./offline/types";
import { PortalDeliveryDrawer } from "./PortalDeliveryDrawer";

/**
 * El panel de entrega del portal (V70), en un teléfono de 390 px: la hoja sale por abajo, las
 * reglas del servidor se comprueban antes de subir nada, la entrega viaja en un solo multipart
 * con su clave, y sin red se queda en el teléfono con la MISMA clave y la misma hora.
 */

const COMPANY = "11111111-1111-4111-8111-111111111111";
const TRIP = "22222222-2222-4222-8222-222222222222";
const STOP = "33333333-3333-4333-8333-333333333333";
const USER = "u-1";

const ORDER: ShownOrder = {
  orderId: "44444444-4444-4444-8444-444444444444", orderNumber: "TO-00000042", customerName: "Bodega San Martín",
  assignedWeightKg: 1000, assignedVolumeM3: 10, assignedPallets: 10, delivery: null,
  pendingSync: false, syncFailed: false,
};

let store: ReturnType<typeof createMemoryStore>;
let fetchMock: ReturnType<typeof vi.fn>;

function renderDrawer(overrides: { evidenceEnabled?: boolean; onSaved?: (outcome: string) => void } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <PortalOfflineProvider userId={USER} store={store} send={() => Promise.reject(new TypeError("Failed to fetch"))}>
        <PortalDeliveryDrawer
          companyId={COMPANY} tripId={TRIP} shipmentNumber="SH-0042" stopId={STOP}
          stopLabel="Parada 1 · Bodega San Martín" order={ORDER}
          evidenceEnabled={overrides.evidenceEnabled ?? true}
          onClose={() => {}} onSaved={overrides.onSaved}
        />
      </PortalOfflineProvider>
    </QueryClientProvider>,
  );
}

function bodyOf(call: unknown[]): FormData {
  return (call[1] as RequestInit).body as FormData;
}

function headerOf(call: unknown[], name: string): string | undefined {
  return ((call[1] as RequestInit).headers as Record<string, string>)[name];
}

const TRIP_DETAIL = {
  trip: { tripId: TRIP, shipmentNumber: "SH-0042", status: "IN_TRANSIT" }, evidenceEnabled: true, stops: [], incidents: [],
};

beforeEach(() => {
  emulatePhoneViewport(390);
  store = createMemoryStore();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("el panel de entrega del portal a 390 px", () => {
  it("en el teléfono es una hoja que sube desde abajo, con los cinco resultados como botones grandes", () => {
    renderDrawer();

    const dialog = screen.getByRole("dialog", { name: "Entrega del pedido TO-00000042" });
    expect(dialog.closest(".MuiDrawer-root")?.className).toContain("MuiDrawer-anchorBottom");
    for (const label of ["Entregado completo", "Entrega parcial", "Rechazado por el cliente", "No se pudo entregar",
      "No se intentó (sigue en el camión)"]) {
      expect(screen.getByRole("button", { name: label })).toBeInTheDocument();
    }
    expect(screen.getByText(/Cargado: 10 pallets · 1[.,]?000 kg · 10 m³/)).toBeInTheDocument();
  });

  it("la misma hoja, en un escritorio, sale por la derecha: la de 390 px no es casualidad", () => {
    emulatePhoneViewport(1280);
    renderDrawer();

    expect(screen.getByRole("dialog").closest(".MuiDrawer-root")?.className).toContain("MuiDrawer-anchorRight");
  });

  it("una entrega parcial pide cuánto rechazó y qué pasó, y no sube nada hasta tenerlo", async () => {
    renderDrawer();

    await userEvent.click(screen.getByRole("button", { name: "Entrega parcial" }));
    await userEvent.click(screen.getByRole("button", { name: "Guardar entrega" }));

    expect(await screen.findByText("Indica cuánto rechazó el cliente.")).toBeInTheDocument();
    expect(screen.getByText("Cuenta qué pasó: es obligatorio cuando no se entregó todo.")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Pallets"), "11");
    await userEvent.click(screen.getByRole("button", { name: "Guardar entrega" }));
    expect(await screen.findByText("No se puede rechazar más de lo que se cargó para este pedido.")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(store.outbox.size).toBe(0);
  });

  it("con red envía un solo multipart con el resultado, lo rechazado, quién recibió y la clave", async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify(TRIP_DETAIL), {
      status: 200, headers: { "content-type": "application/json" },
    }));
    const onSaved = vi.fn();
    renderDrawer({ onSaved });

    await userEvent.click(screen.getByRole("button", { name: "Entrega parcial" }));
    await userEvent.type(screen.getByLabelText("Pallets"), "2");
    await userEvent.type(screen.getByLabelText("Quién recibió (opcional)"), "M. Quispe");
    await userEvent.type(screen.getByLabelText("Qué pasó (obligatorio)"), "Dos pallets con film roto");
    await userEvent.click(screen.getByRole("button", { name: "Guardar entrega" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("sent"));
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain(`/portal/trips/${TRIP}/stops/${STOP}/orders/${ORDER.orderId}/delivery`);
    expect(init.method).toBe("POST");
    expect(headerOf(fetchMock.mock.calls[0], "Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/);
    const body = bodyOf(fetchMock.mock.calls[0]);
    expect(body.get("result")).toBe("PARTIAL");
    expect(body.get("refusedPallets")).toBe("2");
    expect(body.get("refusedWeightKg")).toBeNull();
    expect(body.get("receiverName")).toBe("M. Quispe");
    expect(body.get("notes")).toBe("Dos pallets con film roto");
    expect(body.get("deliveredAt")).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(store.outbox.size).toBe(0);
  });

  it("si el envío se queda sin red, la entrega queda en el teléfono con la misma clave y la misma hora", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const onSaved = vi.fn();
    renderDrawer({ onSaved });

    await userEvent.click(screen.getByRole("button", { name: "Entregado completo" }));
    await userEvent.click(screen.getByRole("button", { name: "Guardar entrega" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("queued"));
    const [entry] = [...store.outbox.values()] as OutboxEntry[];
    expect(entry.kind).toBe("EXECUTION");
    const execution = entry as ExecutionOutboxEntry;
    expect(execution.idempotencyKey).toBe(headerOf(fetchMock.mock.calls[0], "Idempotency-Key"));
    expect(execution.action).toMatchObject({
      type: "DELIVERY", stopId: STOP, orderId: ORDER.orderId,
      input: { result: "DELIVERED", deliveredAt: bodyOf(fetchMock.mock.calls[0]).get("deliveredAt") },
    });
    expect(execution.title).toContain("TO-00000042");
  });

  it("sin almacén de evidencias lo dice, no ofrece cámara, y el resultado se puede registrar igual", async () => {
    vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
    const onSaved = vi.fn();
    renderDrawer({ evidenceEnabled: false, onSaved });

    await userEvent.click(screen.getByRole("button", { name: "Entregado completo" }));
    expect(screen.getByText("Este sistema no guarda fotos ni firmas: el resultado se registra sin evidencia.")).toBeInTheDocument();
    expect(screen.queryByLabelText("Fotos")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /firma/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Guardar sin conexión" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("queued"));
    expect(fetchMock).not.toHaveBeenCalled();
    const [entry] = [...store.outbox.values()] as ExecutionOutboxEntry[];
    expect(entry.action).toMatchObject({ type: "DELIVERY", input: { result: "DELIVERED", photos: [], signature: null } });
  });

  it("un pedido no intentado no lleva hora, ni receptor, ni fotos", async () => {
    vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
    renderDrawer();

    await userEvent.click(screen.getByRole("button", { name: "No se intentó (sigue en el camión)" }));
    expect(screen.queryByLabelText("Quién recibió (opcional)")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Fotos")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Guardar sin conexión" }));

    await waitFor(() => expect(store.outbox.size).toBe(1));
    const [entry] = [...store.outbox.values()] as ExecutionOutboxEntry[];
    expect(entry.action).toMatchObject({ type: "DELIVERY", input: { result: "NOT_ATTEMPTED", deliveredAt: null, receiverName: null } });
  });
});
