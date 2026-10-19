import { describe, expect, it } from "vitest";
import { enumLabel } from "./enums";

describe("enumLabel", () => {
  it("nombra las retenciones que pone el almacén (V61)", () => {
    expect(enumLabel("holdReasonCode", "WAREHOUSE_CANCELLED")).toBe("El almacén anuló su pedido");
    expect(enumLabel("holdReasonCode", "WAREHOUSE_SHORT_CLOSED")).toBe("El almacén cerró el pedido con saldo");
  });

  it("deja tal cual un código que no conoce", () => {
    expect(enumLabel("holdReasonCode", "CREDIT_LIMIT")).toBe("CREDIT_LIMIT");
    expect(enumLabel("holdReasonCode", null)).toBe("-");
  });
});
