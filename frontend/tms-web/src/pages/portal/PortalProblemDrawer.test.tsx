import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { emulatePhoneViewport } from "../../test/phoneViewport";
import { createMemoryStore } from "./offline/memoryStore";
import { PortalOfflineProvider } from "./offline/PortalOfflineProvider";
import type { ExecutionOutboxEntry } from "./offline/types";
import { PortalProblemDrawer } from "./PortalProblemDrawer";

/** Incidencias y paradas no atendidas desde el portal (V70), en un teléfono de 390 px. */

const COMPANY = "11111111-1111-4111-8111-111111111111";
const TRIP = "22222222-2222-4222-8222-222222222222";

let store: ReturnType<typeof createMemoryStore>;
let fetchMock: ReturnType<typeof vi.fn>;

function renderDrawer(props: { mode: "incident" | "fail"; stop: { id: string; label: string } | null; onSaved?: (o: string) => void }) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <PortalOfflineProvider userId="u-1" store={store} send={() => Promise.reject(new TypeError("Failed to fetch"))}>
        <PortalProblemDrawer companyId={COMPANY} tripId={TRIP} shipmentNumber="SH-0042" onClose={() => {}} {...props} />
      </PortalOfflineProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  emulatePhoneViewport(390);
  store = createMemoryStore();
  fetchMock = vi.fn(async () => new Response(JSON.stringify({ trip: { tripId: TRIP }, stops: [], incidents: [] }), {
    status: 200, headers: { "content-type": "application/json" },
  }));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("reportar un problema desde el portal", () => {
  it("una incidencia del viaje entero no ofrece los tipos que solo valen en una parada", () => {
    renderDrawer({ mode: "incident", stop: null });

    expect(screen.getByRole("radio", { name: "Demora por tráfico" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Avería del vehículo" })).toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Cliente cerrado" })).not.toBeInTheDocument();
  });

  it("«Otra» exige contarlo, y no se envía nada hasta entonces", async () => {
    renderDrawer({ mode: "incident", stop: null });

    await userEvent.click(screen.getByRole("radio", { name: "Otra" }));
    await userEvent.click(screen.getByRole("button", { name: "Reportar" }));

    expect(await screen.findByText("Con «Otra», cuenta qué pasó.")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("una parada no atendida se envía como la oficina la registra: con su motivo y su clave", async () => {
    const onSaved = vi.fn();
    renderDrawer({ mode: "fail", stop: { id: "s-1", label: "Parada 1 · Bodega" }, onSaved });

    await userEvent.click(screen.getByRole("radio", { name: "Cliente cerrado" }));
    await userEvent.click(screen.getByRole("button", { name: "Guardar como no atendida" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("sent"));
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain(`/portal/trips/${TRIP}/stops/s-1/fail`);
    expect(JSON.parse(init.body as string)).toMatchObject({ exceptionType: "CUSTOMER_CLOSED" });
    expect((init.headers as Record<string, string>)["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("sin señal la incidencia queda en el teléfono y se dice", async () => {
    vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
    const onSaved = vi.fn();
    renderDrawer({ mode: "incident", stop: { id: "s-2", label: "Parada 2" }, onSaved });

    expect(screen.getByText(/Sin conexión. Se guardará en el teléfono/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: "Cliente cerrado" }));
    await userEvent.click(screen.getByRole("button", { name: "Reportar" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("queued"));
    expect(fetchMock).not.toHaveBeenCalled();
    const [entry] = [...store.outbox.values()] as ExecutionOutboxEntry[];
    expect(entry).toMatchObject({ kind: "EXECUTION", tripId: TRIP,
      action: { type: "INCIDENT", tripStopId: "s-2", exceptionType: "CUSTOMER_CLOSED" } });
  });
});
