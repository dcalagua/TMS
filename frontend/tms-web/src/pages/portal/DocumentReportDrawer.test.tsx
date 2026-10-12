import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PortalDocument } from "../../shared/api/portalApi";
import { DocumentReportDrawer } from "./DocumentReportDrawer";
import { createMemoryStore } from "./offline/memoryStore";
import { PortalOfflineProvider } from "./offline/PortalOfflineProvider";
import type { OutboxEntry } from "./offline/types";

/**
 * El panel de reporte del portal de transportistas (ADR-018, ADR-019 §1).
 *
 * Lo que se protege son las reglas que, rotas, cuestan caro en la puerta de un cliente: una
 * entrega sin foto no sale, un rechazo sin motivo no sale, y un reporte que no pudo salir por
 * falta de red se guarda en el teléfono con la MISMA clave de idempotencia y la misma hora — si
 * llevara otra, un reporte que sí llegó al servidor se registraría dos veces.
 */

const COMPANY = "11111111-1111-4111-8111-111111111111";
const TRIP = "22222222-2222-4222-8222-222222222222";
const USER = "u-1";

const DOCUMENT: PortalDocument = {
  id: "33333333-3333-4333-8333-333333333333",
  documentId: "44444444-4444-4444-8444-444444444444",
  documentType: "INVOICE",
  documentNumber: "F001-000123",
  orderNumbers: ["TO-00000001"],
  customerName: "Bodega San Martín",
  status: "PENDING",
  reasonCode: null,
  remark: null,
  reportedAt: null,
  recordedAt: null,
  returnedAt: null,
  editable: true,
  evidence: [],
};

let store: ReturnType<typeof createMemoryStore>;
/** El envío de la cola. Por defecto sin red: lo que se encola se queda en el teléfono. */
let queueSend: ReturnType<typeof vi.fn<(entry: OutboxEntry) => Promise<PortalDocument>>>;

function renderDrawer(overrides: {
  document?: Partial<PortalDocument>;
  evidenceEnabled?: boolean;
  onClose?: () => void;
  onSaved?: (outcome: "sent" | "queued") => void;
} = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <PortalOfflineProvider userId={USER} store={store} send={queueSend}>
        <DocumentReportDrawer
          companyId={COMPANY}
          tripId={TRIP}
          shipmentNumber="SH-0001"
          document={{ ...DOCUMENT, ...overrides.document }}
          evidenceEnabled={overrides.evidenceEnabled ?? true}
          onClose={overrides.onClose ?? (() => {})}
          onSaved={overrides.onSaved}
        />
      </PortalOfflineProvider>
    </QueryClientProvider>,
  );
}

function reported(status: PortalDocument["status"]): Response {
  return new Response(JSON.stringify({ ...DOCUMENT, status, reportedAt: "2026-10-12T15:00:00Z" }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function headerOf(call: unknown[], name: string): string | undefined {
  const init = call[1] as RequestInit;
  return (init.headers as Record<string, string>)[name];
}

function bodyOf(call: unknown[]): FormData {
  return (call[1] as RequestInit).body as FormData;
}

async function chooseNotDelivered() {
  await userEvent.click(screen.getByRole("button", { name: "No entregado" }));
  await userEvent.click(screen.getByRole("radio", { name: "Cliente ausente" }));
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  store = createMemoryStore();
  queueSend = vi.fn<(entry: OutboxEntry) => Promise<PortalDocument>>(() => Promise.reject(new TypeError("Failed to fetch")));
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("el panel de reporte del portal", () => {
  it("no guarda «Entregado» sin al menos una foto", async () => {
    renderDrawer();

    await userEvent.click(screen.getByRole("button", { name: "Entregado" }));
    await userEvent.click(screen.getByRole("button", { name: "Guardar entrega" }));

    expect(await screen.findByText("Para marcarlo entregado hace falta al menos una foto.")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(store.outbox.size).toBe(0);
  });

  it("no guarda un rechazo sin motivo, y los motivos son una lista grande de opciones", async () => {
    renderDrawer();

    await userEvent.click(screen.getByRole("button", { name: "Rechazado" }));
    expect(screen.getAllByRole("radio")).toHaveLength(7);
    await userEvent.click(screen.getByRole("button", { name: "Guardar rechazo" }));

    expect(await screen.findByText("Elige el motivo.")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("con foto y red, envía la entrega como multipart con las partes del contrato", async () => {
    fetchMock.mockResolvedValue(reported("DELIVERED"));
    const onSaved = vi.fn();
    renderDrawer({ onSaved });

    await userEvent.click(screen.getByRole("button", { name: "Entregado" }));
    const photo = new File([new Uint8Array([1, 2, 3])], "puerta.jpg", { type: "image/jpeg" });
    fireEvent.change(screen.getByLabelText("Fotos"), { target: { files: [photo] } });
    expect(await screen.findByText("1/5 fotos")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Guardar entrega" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain(`/portal/trips/${TRIP}/documents/${DOCUMENT.documentId}/report`);
    expect(init.method).toBe("POST");
    const body = bodyOf(fetchMock.mock.calls[0]);
    expect(body.get("status")).toBe("DELIVERED");
    expect(body.get("reasonCode")).toBeNull();
    expect(body.getAll("photos")).toHaveLength(1);
    expect(body.get("reportedAt")).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("sent", expect.anything()));
    expect(store.outbox.size).toBe(0);
  });

  it("si el envío se queda sin red, lo guarda en el teléfono con la misma clave y la misma hora", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const onSaved = vi.fn();
    const onClose = vi.fn();
    renderDrawer({ onSaved, onClose });

    await chooseNotDelivered();
    await userEvent.click(screen.getByRole("button", { name: "Guardar no entrega" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("queued", expect.anything()));
    expect(onClose).toHaveBeenCalled();

    const [entry] = [...store.outbox.values()] as OutboxEntry[];
    const sentKey = headerOf(fetchMock.mock.calls[0], "Idempotency-Key");
    expect(sentKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(entry).toMatchObject({
      userId: USER, companyId: COMPANY, tripId: TRIP, documentId: DOCUMENT.documentId,
      idempotencyKey: sentKey, status: "NOT_DELIVERED", reasonCode: "CUSTOMER_ABSENT",
      reportedAt: bodyOf(fetchMock.mock.calls[0]).get("reportedAt"),
      documentNumber: "F001-000123", shipmentNumber: "SH-0001",
    });
  });

  it("sin conexión no intenta la red: lo guarda directamente y lo dice en el botón", async () => {
    vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
    const onSaved = vi.fn();
    renderDrawer({ onSaved });

    expect(screen.getByText(/Sin conexión. El reporte se guardará en el teléfono/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Rechazado" }));
    await userEvent.click(screen.getByRole("radio", { name: "Cliente rechazó" }));
    await userEvent.click(screen.getByRole("button", { name: "Guardar sin conexión" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("queued", expect.anything()));
    expect(fetchMock).not.toHaveBeenCalled();
    expect([...store.outbox.values()][0]).toMatchObject({ status: "REJECTED", reasonCode: "CUSTOMER_REFUSED", state: "PENDING" });
  });

  it("con un reporte de ese documento aún en cola, el nuevo va detrás aunque haya red", async () => {
    await store.putOutbox({
      id: "older", userId: USER, companyId: COMPANY, tripId: TRIP, documentId: DOCUMENT.documentId, idempotencyKey: "k-old",
      status: "REJECTED", reasonCode: "CUSTOMER_REFUSED", remark: null, reportedAt: "2026-10-12T10:00:00.000Z",
      photos: [], signature: null, createdAt: "2026-10-12T10:00:00.000Z", attempts: 1, lastError: null,
      state: "PENDING", nextAttemptAt: "2099-01-01T00:00:00.000Z", documentType: "INVOICE",
      documentNumber: "F001-000123", customerName: null, shipmentNumber: "SH-0001",
    });
    const onSaved = vi.fn();
    renderDrawer({ onSaved });
    await waitFor(() => expect(screen.getByRole("button", { name: "Guardar" })).toBeInTheDocument());

    await chooseNotDelivered();
    await userEvent.click(screen.getByRole("button", { name: "Guardar no entrega" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("queued", expect.anything()));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(store.outbox.size).toBe(2);
  });

  it("un rechazo de reglas del servidor se enseña y no va a la cola", async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ code: "conflict", detail: "La oficina ya cerró el documento." }), {
      status: 409, headers: { "content-type": "application/problem+json" },
    }));
    renderDrawer();

    await chooseNotDelivered();
    await userEvent.click(screen.getByRole("button", { name: "Guardar no entrega" }));

    expect(await screen.findByText("La oficina ya cerró el documento.")).toBeInTheDocument();
    expect(store.outbox.size).toBe(0);
  });

  it("cada apertura del panel lleva su propia clave", async () => {
    fetchMock.mockImplementation(async () => reported("REJECTED"));

    const keys: (string | undefined)[] = [];
    for (let i = 0; i < 2; i++) {
      const view = renderDrawer();
      await userEvent.click(screen.getByRole("button", { name: "Rechazado" }));
      await userEvent.click(screen.getByRole("radio", { name: "Cliente rechazó" }));
      await userEvent.click(screen.getByRole("button", { name: "Guardar rechazo" }));
      await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(i + 1));
      keys.push(headerOf(fetchMock.mock.calls[i], "Idempotency-Key"));
      view.unmount();
    }

    expect(keys[0]).not.toBe(keys[1]);
  });

  it("con el motivo «Otro» exige la observación", async () => {
    renderDrawer();

    await userEvent.click(screen.getByRole("button", { name: "Rechazado" }));
    await userEvent.click(screen.getByRole("radio", { name: "Otro" }));
    await userEvent.click(screen.getByRole("button", { name: "Guardar rechazo" }));

    expect(await screen.findByText("Con el motivo «Otro», cuenta qué pasó.")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sin almacén de evidencias, «Entregado» está deshabilitado y se explica", () => {
    renderDrawer({ evidenceEnabled: false });

    expect(screen.getByRole("button", { name: "Entregado" })).toBeDisabled();
    expect(screen.getByText(/No se puede marcar «Entregado»/)).toBeInTheDocument();
  });

  it("con el cargo ya recibido no deja editar: cerrado por oficina", () => {
    renderDrawer({ document: { status: "DELIVERED", editable: false, returnedAt: "2026-10-12T18:00:00Z" } });

    expect(screen.getByText(/La oficina ya recibió el cargo/)).toBeInTheDocument();
    expect(screen.getByText(/Cerrado por oficina/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Guardar/ })).not.toBeInTheDocument();
  });
});
