import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../../shared/api/httpClient";
import type { PortalDocument } from "../../../shared/api/portalApi";
import { createMemoryStore } from "./memoryStore";
import { BACKOFF_BASE_MS, backoffDelay, createOutboxEngine, type DocumentOutboxDraft } from "./outboxEngine";
import type { DocumentOutboxEntry, ExecutionOutboxEntry, OutboxEntry } from "./types";

/**
 * La cola de reportes sin señal (ADR-019 §1). Lo que se protege: nada se pierde en silencio,
 * nada se envía dos veces a la vez, un rechazo del servidor se ve con su razón, y una red caída no
 * se machaca cada segundo.
 */

const USER = "u-1";
const COMPANY = "c-1";
const TRIP = "t-1";

function draft(overrides: Partial<DocumentOutboxDraft> = {}): DocumentOutboxDraft {
  return {
    companyId: COMPANY,
    tripId: TRIP,
    documentId: "d-1",
    idempotencyKey: crypto.randomUUID(),
    status: "NOT_DELIVERED",
    reasonCode: "CUSTOMER_ABSENT",
    remark: null,
    reportedAt: "2026-10-12T14:00:00.000Z",
    photos: [],
    signature: null,
    documentType: "INVOICE",
    documentNumber: "F001-1",
    customerName: "Bodega",
    shipmentNumber: "SH-1",
    ...overrides,
  };
}

function problem(status: number, code: string, detail: string): ApiError {
  return new ApiError(status, { status, code, detail }, "corr", `falló con ${status}`);
}

const OK = { documentId: "d-1" } as PortalDocument;

function setup(options: { online?: () => boolean } = {}) {
  const store = createMemoryStore();
  let clock = Date.parse("2026-10-12T15:00:00.000Z");
  let seq = 0;
  const send = vi.fn<(entry: OutboxEntry) => Promise<unknown>>();
  const onSent = vi.fn();
  const engine = createOutboxEngine({
    store,
    userId: USER,
    send,
    onSent,
    now: () => clock,
    newId: () => `e-${++seq}`,
    isOnline: options.online ?? (() => true),
  });
  return {
    store, engine, send, onSent,
    advance: (ms: number) => { clock += ms; },
  };
}

describe("la cola de reportes", () => {
  it("guarda el reporte entero, con su clave y su hora, como PENDING", async () => {
    const { engine, store } = setup();
    const photo = new Blob([new Uint8Array([1, 2])], { type: "image/jpeg" });

    const entry = await engine.enqueue(draft({ photos: [photo], idempotencyKey: "k-1" }));

    const stored = await store.getOutbox(entry.id);
    expect(stored).toMatchObject({
      userId: USER, state: "PENDING", attempts: 0, idempotencyKey: "k-1", reportedAt: "2026-10-12T14:00:00.000Z",
    });
    expect((stored as DocumentOutboxEntry | null)?.photos).toEqual([photo]);
  });

  it("un 2xx borra la entrada y avisa para invalidar el viaje", async () => {
    const { engine, store, send, onSent } = setup();
    send.mockResolvedValue(OK);
    await engine.enqueue(draft({ idempotencyKey: "k-1" }));

    const result = await engine.sync();

    expect(result.sent).toBe(1);
    expect(send).toHaveBeenCalledWith(expect.objectContaining({ idempotencyKey: "k-1", reportedAt: "2026-10-12T14:00:00.000Z" }));
    expect(await store.listOutbox(USER)).toEqual([]);
    expect(onSent).toHaveBeenCalledWith(expect.objectContaining({ tripId: TRIP }), OK);
  });

  it("un 409 la deja FAILED con la razón del servidor y no la reintenta sola", async () => {
    const { engine, store, send } = setup();
    send.mockRejectedValue(problem(409, "conflict", "La oficina ya confirmó el cargo de este documento."));
    await engine.enqueue(draft());

    const result = await engine.sync();
    expect(result.failed).toBe(1);
    const [entry] = await store.listOutbox(USER);
    expect(entry).toMatchObject({ state: "FAILED", lastError: "La oficina ya confirmó el cargo de este documento." });

    await engine.sync();
    expect(send).toHaveBeenCalledTimes(1);
  });

  it.each([400, 403, 404])("un %i también la deja FAILED", async (status) => {
    const { engine, store, send } = setup();
    send.mockRejectedValue(problem(status, "validation-failed", "No."));
    await engine.enqueue(draft());

    await engine.sync();

    expect((await store.listOutbox(USER))[0].state).toBe("FAILED");
  });

  it("un 423 de cuenta suspendida la deja PENDING para enviarla sola al reactivar (ADR-021)", async () => {
    const { engine, store, send, advance } = setup();
    send.mockRejectedValueOnce(problem(423, "tenant-suspended", "Suspended."));
    await engine.enqueue(draft());

    await engine.sync();
    expect((await store.listOutbox(USER))[0].state).toBe("PENDING");

    send.mockResolvedValueOnce(OK);
    advance(BACKOFF_BASE_MS * 64);
    await engine.sync();
    expect(await store.listOutbox(USER)).toHaveLength(0);
  });

  it("sin red: se queda PENDING con espera exponencial y no se reintenta antes de tiempo", async () => {
    const { engine, store, send, advance } = setup();
    send.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    await engine.enqueue(draft());

    await engine.sync();
    let [entry] = await store.listOutbox(USER);
    expect(entry).toMatchObject({ state: "PENDING", attempts: 1 });
    expect(Date.parse(entry.nextAttemptAt!) - Date.parse("2026-10-12T15:00:00.000Z")).toBe(BACKOFF_BASE_MS);

    // Antes de que venza la espera, el envío automático no la toca.
    advance(BACKOFF_BASE_MS - 1_000);
    const early = await engine.sync();
    expect(send).toHaveBeenCalledTimes(1);
    expect(early.deferred).toBe(1);

    // Vencida, sí.
    send.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    advance(2_000);
    await engine.sync();
    expect(send).toHaveBeenCalledTimes(2);
    [entry] = await store.listOutbox(USER);
    expect(entry.attempts).toBe(2);
    expect(Date.parse(entry.nextAttemptAt!) - (Date.parse("2026-10-12T15:00:00.000Z") + BACKOFF_BASE_MS + 1_000))
      .toBe(backoffDelay(2));
  });

  it("«Sincronizar ahora» (force) ignora la espera", async () => {
    const { engine, send } = setup();
    send.mockRejectedValueOnce(new TypeError("Failed to fetch")).mockResolvedValueOnce(OK);
    await engine.enqueue(draft());
    await engine.sync();

    const result = await engine.sync({ force: true });

    expect(result.sent).toBe(1);
    expect(send).toHaveBeenCalledTimes(2);
  });

  it("la espera crece hasta un tope", () => {
    expect([1, 2, 3, 4].map(backoffDelay)).toEqual([5_000, 10_000, 20_000, 40_000]);
    expect(backoffDelay(30)).toBe(5 * 60_000);
  });

  it("de la más antigua a la más nueva, y un documento no adelanta a su reporte anterior", async () => {
    const { engine, store, send, advance } = setup();
    await engine.enqueue(draft({ documentId: "d-1", idempotencyKey: "first" }));
    advance(1_000);
    await engine.enqueue(draft({ documentId: "d-2", idempotencyKey: "second" }));
    advance(1_000);
    await engine.enqueue(draft({ documentId: "d-1", idempotencyKey: "correction" }));

    // El primero falla por 5xx: el resto de documentos sigue, pero su corrección espera.
    send.mockImplementation(async (entry) => {
      if (entry.idempotencyKey === "first") throw problem(503, "internal-error", "caído");
      return OK;
    });
    await engine.sync();

    expect(send.mock.calls.map(([entry]) => entry.idempotencyKey)).toEqual(["first", "second"]);
    expect((await store.listOutbox(USER)).map((entry) => entry.idempotencyKey)).toEqual(["first", "correction"]);
  });

  it("V70: las acciones de las paradas de un viaje salen en orden, y una retenida retiene a las siguientes", async () => {
    const { engine, store, send, advance } = setup();
    const execution = (key: string, tripId: string, action: ExecutionOutboxEntry["action"]) => engine.enqueue({
      kind: "EXECUTION", companyId: COMPANY, tripId, idempotencyKey: key, action, title: key,
      reportedAt: "2026-10-12T14:00:00.000Z", shipmentNumber: "SH-1",
    });
    await execution("arrive", TRIP, { type: "STOP", stopId: "s-1", transition: "arrive", occurredAt: "2026-10-12T14:00:00.000Z" });
    advance(1_000);
    await execution("delivery", TRIP, { type: "DELIVERY", stopId: "s-1", orderId: "o-1",
      input: { result: "DELIVERED", deliveredAt: "2026-10-12T14:05:00.000Z", photos: [] } });
    advance(1_000);
    await engine.enqueue(draft({ documentId: "d-9", idempotencyKey: "document" }));
    advance(1_000);
    await execution("other-trip", "t-2", { type: "STOP", stopId: "s-9", transition: "arrive", occurredAt: "2026-10-12T14:00:00.000Z" });

    // La llegada falla por 5xx: la entrega de esa parada no puede adelantarla. Un documento del
    // mismo viaje y otro viaje no dependen de ella.
    send.mockImplementation(async (entry) => {
      if (entry.idempotencyKey === "arrive") throw problem(503, "internal-error", "caído");
      return OK;
    });
    await engine.sync();

    expect(send.mock.calls.map(([entry]) => entry.idempotencyKey)).toEqual(["arrive", "document", "other-trip"]);
    expect((await store.listOutbox(USER)).map((entry) => entry.idempotencyKey)).toEqual(["arrive", "delivery"]);
  });

  it("un fallo de red corta la pasada: el resto tampoco saldría", async () => {
    const { engine, send } = setup();
    send.mockRejectedValue(new TypeError("Failed to fetch"));
    await engine.enqueue(draft({ documentId: "d-1" }));
    await engine.enqueue(draft({ documentId: "d-2" }));

    await engine.sync();

    expect(send).toHaveBeenCalledTimes(1);
  });

  it("un 401 pausa la cola sin gastar intentos, hasta que vuelva la sesión", async () => {
    const { engine, store, send } = setup();
    send.mockRejectedValueOnce(problem(401, "invalid-token", "Token caducado"));
    await engine.enqueue(draft());

    const result = await engine.sync();
    expect(result.paused).toBe(true);
    expect((await store.listOutbox(USER))[0]).toMatchObject({ state: "PENDING", attempts: 0 });

    await engine.sync({ force: true });
    expect(send).toHaveBeenCalledTimes(1);

    send.mockResolvedValueOnce(OK);
    engine.resume();
    await engine.sync();
    expect(send).toHaveBeenCalledTimes(2);
    expect(await store.listOutbox(USER)).toEqual([]);
  });

  it("sin red no intenta nada", async () => {
    const { engine, send } = setup({ online: () => false });
    await engine.enqueue(draft());

    await engine.sync({ force: true });

    expect(send).not.toHaveBeenCalled();
  });

  it("dos disparadores a la vez hacen una sola pasada: la entrada sale una vez", async () => {
    const { engine, send } = setup();
    let release: (doc: PortalDocument) => void = () => undefined;
    send.mockImplementation(() => new Promise((resolve) => { release = resolve; }));
    await engine.enqueue(draft());

    const first = engine.sync();
    const second = engine.sync();
    await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(1));
    release(OK);
    await Promise.all([first, second]);

    expect(send).toHaveBeenCalledTimes(1);
  });

  it("un envío cortado por cerrar la app vuelve a PENDING al arrancar", async () => {
    const { engine, store } = setup();
    const entry = await engine.enqueue(draft());
    await store.putOutbox({ ...entry, state: "SENDING" });

    await engine.recover();

    expect((await store.getOutbox(entry.id))?.state).toBe("PENDING");
  });

  it("reintentar una FAILED la devuelve a la cola; descartarla la borra", async () => {
    const { engine, store, send } = setup();
    send.mockRejectedValueOnce(problem(400, "validation-failed", "Falta el motivo."));
    const entry = await engine.enqueue(draft());
    await engine.sync();

    await engine.retry(entry.id);
    expect((await store.getOutbox(entry.id))).toMatchObject({ state: "PENDING", lastError: null });

    await engine.discard(entry.id);
    expect(await store.getOutbox(entry.id)).toBeNull();
  });

  it("solo ve la cola de su usuario", async () => {
    const { engine, store, send } = setup();
    send.mockResolvedValue(OK);
    const other = await engine.enqueue(draft());
    await store.putOutbox({ ...other, id: "foreign", userId: "u-2" });
    await store.deleteOutbox(other.id);

    await engine.sync();

    expect(send).not.toHaveBeenCalled();
    expect(await store.getOutbox("foreign")).not.toBeNull();
  });
});
