import { describe, expect, it } from "vitest";
import { buildDeliveryFormData } from "../../shared/api/portalApi";
import { parseAmount, validateDelivery, type DeliveryFormValues } from "./portalExecution";

/**
 * Las reglas del formulario de entrega del portal (V70): las del servidor, antes de subir nada.
 */

const LOADED = { weightKg: 1000, volumeM3: 10, pallets: 10 };

function values(overrides: Partial<DeliveryFormValues> = {}): DeliveryFormValues {
  return {
    result: "DELIVERED", receiverName: "", receiverDocument: "", notes: "", refusedWeightKg: "", refusedVolumeM3: "",
    refusedPallets: "", photos: [], signature: null, ...overrides,
  };
}

describe("las reglas de una entrega desde el portal", () => {
  it("una entrega completa no pide nada más: la foto es opcional", () => {
    expect(validateDelivery(values(), LOADED)).toEqual({});
  });

  it("sin resultado no hay nada que guardar", () => {
    expect(validateDelivery(values({ result: null }), LOADED).result).toBeDefined();
  });

  it("parcial, rechazado y fallido se explican; no intentado no", () => {
    for (const result of ["PARTIAL", "REJECTED", "FAILED"] as const) {
      expect(validateDelivery(values({ result, refusedPallets: "1" }), LOADED).notes).toBeDefined();
    }
    expect(validateDelivery(values({ result: "NOT_ATTEMPTED" }), LOADED)).toEqual({});
  });

  it("una parcial dice cuánto se rechazó, sin negativos y sin pasar de lo cargado", () => {
    const partial = (refused: Partial<DeliveryFormValues>) =>
      validateDelivery(values({ result: "PARTIAL", notes: "x", ...refused }), LOADED).refused;
    expect(partial({})).toBe("Indica cuánto rechazó el cliente.");
    expect(partial({ refusedPallets: "0" })).toBe("Indica cuánto rechazó el cliente.");
    expect(partial({ refusedPallets: "-1" })).toBe("Escribe cantidades válidas, sin negativos.");
    expect(partial({ refusedWeightKg: "abc" })).toBe("Escribe cantidades válidas, sin negativos.");
    expect(partial({ refusedWeightKg: "1001" })).toBe("No se puede rechazar más de lo que se cargó para este pedido.");
    expect(partial({ refusedPallets: "2,5" })).toBeUndefined();
  });

  it("acepta la coma decimal que teclea un teléfono en español", () => {
    expect(parseAmount("2,5")).toBe(2.5);
    expect(parseAmount("  ")).toBeNull();
    expect(Number.isNaN(parseAmount("dos"))).toBe(true);
  });

  it("el multipart lleva las partes del contrato y omite lo vacío", () => {
    const form = buildDeliveryFormData({
      result: "PARTIAL", deliveredAt: "2026-10-12T14:00:00.000Z", receiverName: "M. Quispe", receiverDocument: null,
      notes: "1 roto", refusedPallets: 1, refusedWeightKg: null, photos: [new Blob(["x"], { type: "image/jpeg" })],
      signature: new Blob(["y"], { type: "image/png" }),
    });

    expect(form.get("result")).toBe("PARTIAL");
    expect(form.get("refusedPallets")).toBe("1");
    expect(form.get("refusedWeightKg")).toBeNull();
    expect(form.get("receiverDocument")).toBeNull();
    expect(form.getAll("photos")).toHaveLength(1);
    expect(form.get("signature")).not.toBeNull();
  });
});
