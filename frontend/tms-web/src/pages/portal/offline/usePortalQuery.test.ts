import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../../shared/api/httpClient";
import { createMemoryStore } from "./memoryStore";
import { OfflineNoDataError } from "./network";
import { readNetworkFirst } from "./usePortalQuery";

/**
 * Las lecturas del portal: red primero, copia en el teléfono si no hay red (ADR-019 §1).
 */

const base = { userId: "u-1", companyId: "c-1", snapshotKey: "trips:14:false" };

function args(fetcher: () => Promise<unknown>, online = true, store = createMemoryStore()) {
  return { ...base, store, fetcher, signal: new AbortController().signal, online: () => online };
}

describe("la lectura red-primero", () => {
  it("con red devuelve lo leído y guarda la copia", async () => {
    const store = createMemoryStore();

    const served = await readNetworkFirst(args(async () => [{ tripId: "t-1" }], true, store));

    expect(served).toEqual({ data: [{ tripId: "t-1" }], savedAt: null });
    await vi.waitFor(async () =>
      expect((await store.getSnapshot("u-1", "c-1", "trips:14:false"))?.data).toEqual([{ tripId: "t-1" }]));
  });

  it("si fetch lanza (sin red), enseña la copia con su hora", async () => {
    const store = createMemoryStore();
    await store.putSnapshot("u-1", "c-1", "trips:14:false", ["copia"], "2026-10-12T09:15:00.000Z");

    const served = await readNetworkFirst(args(() => Promise.reject(new TypeError("Failed to fetch")), true, store));

    expect(served).toEqual({ data: ["copia"], savedAt: "2026-10-12T09:15:00.000Z" });
  });

  it("con el teléfono sin red ni siquiera lo intenta", async () => {
    const store = createMemoryStore();
    await store.putSnapshot("u-1", "c-1", "trips:14:false", ["copia"], "2026-10-12T09:15:00.000Z");
    const fetcher = vi.fn();

    const served = await readNetworkFirst(args(fetcher, false, store));

    expect(fetcher).not.toHaveBeenCalled();
    expect(served.savedAt).toBe("2026-10-12T09:15:00.000Z");
  });

  it("sin red y sin copia, lo dice", async () => {
    await expect(readNetworkFirst(args(() => Promise.reject(new TypeError("Failed to fetch")))))
      .rejects.toBeInstanceOf(OfflineNoDataError);
  });

  it("un error del servidor no se tapa con la copia", async () => {
    const store = createMemoryStore();
    await store.putSnapshot("u-1", "c-1", "trips:14:false", ["copia"]);
    const forbidden = new ApiError(403, { code: "access-denied" }, "c", "403");

    await expect(readNetworkFirst(args(() => Promise.reject(forbidden), true, store))).rejects.toBe(forbidden);
  });

  it("la copia es de su usuario y su empresa", async () => {
    const store = createMemoryStore();
    await store.putSnapshot("u-2", "c-1", "trips:14:false", ["de otro"]);

    await expect(readNetworkFirst(args(() => Promise.reject(new TypeError("x")), true, store)))
      .rejects.toBeInstanceOf(OfflineNoDataError);
  });
});
