import { describe, expect, it } from "vitest";
import { ORDER_STATUSES, REOPENABLE_ORDER_STATUSES } from "./ordersApi";
import type { OrderStatus } from "./ordersApi";
import { enumLabel } from "../../lib/enums";
import { setLang, t } from "../../lib/i18n";

/**
 * El vocabulario del ciclo de vida del pedido, tal como lo ve la interfaz (migración V36).
 *
 * Lo que se protege es lo que un typechecker no ve. `enumLabel` devuelve el propio valor cuando
 * no encuentra traducción, así que un estado nuevo sin etiqueta no rompe la compilación: se
 * publica y el usuario lee `DELIVERY_FAILED` en una celda. Esta prueba es lo que convierte ese
 * olvido en un fallo.
 */
describe("el vocabulario de estados del pedido", () => {
  it("cubre los ocho estados que expone el backend", () => {
    expect(ORDER_STATUSES).toHaveLength(8);
    expect(ORDER_STATUSES).toEqual([
      "NOT_READY",
      "READY_FOR_PLANNING",
      "PLANNED",
      "IN_EXECUTION",
      "DELIVERED",
      "PARTIALLY_DELIVERED",
      "DELIVERY_FAILED",
      "CANCELLED",
    ]);
  });

  it("traduce todos los estados al español, ninguno se queda en el código crudo", () => {
    setLang("es");
    for (const status of ORDER_STATUSES) {
      const label = enumLabel("orderStatus", status);
      expect(label, `${status} no tiene etiqueta`).not.toBe(status);
      expect(label).not.toBe("-");
    }
  });

  it("traduce todos los estados al inglés", () => {
    setLang("en");
    try {
      for (const status of ORDER_STATUSES) {
        const label = enumLabel("orderStatus", status);
        expect(label, `${status} no tiene etiqueta`).not.toBe(status);
      }
      // Los cuatro estados nuevos se tradujeron de verdad, no se quedaron en español.
      expect(t("Entregado")).toBe("Delivered");
      expect(t("Entregado parcialmente")).toBe("Partially delivered");
      expect(t("Entrega fallida")).toBe("Delivery failed");
      expect(t("En ruta")).toBe("On the road");
    } finally {
      setLang("es");
    }
  });

  it("solo se reabre un pedido que volvió corto", () => {
    expect(REOPENABLE_ORDER_STATUSES).toEqual(["PARTIALLY_DELIVERED", "DELIVERY_FAILED"]);

    // Espejo de OrderStatus.isReopenable(): entregado no se reabre porque no queda nada que
    // entregar, y cancelado es terminal.
    const notReopenable: OrderStatus[] = [
      "NOT_READY", "READY_FOR_PLANNING", "PLANNED", "IN_EXECUTION", "DELIVERED", "CANCELLED",
    ];
    for (const status of notReopenable) {
      expect(REOPENABLE_ORDER_STATUSES).not.toContain(status);
    }
  });

  it("todo estado reabrible es un estado conocido", () => {
    for (const status of REOPENABLE_ORDER_STATUSES) {
      expect(ORDER_STATUSES).toContain(status);
    }
  });
});
