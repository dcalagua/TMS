import { describe, expect, it } from "vitest";
import type { ControlTowerBlockerView } from "./controlTowerApi";
import { enumLabel } from "../../lib/enums";

/**
 * El panel de bloqueos de la torre (JOB 12).
 *
 * Lo que se fija aquí es la única regla de lectura que importa: **cero se muestra como cero**. "No
 * hay nada atascado" es un dato que un despachador quiere leer, no deducir de un panel vacío, y un
 * panel que se escondiera cuando no hay bloqueos dejaría al lector sin saber si es que no hay
 * ninguno o si es que nadie miró.
 *
 * Y que los tres motivos tienen etiqueta: un motivo que cayera al valor crudo
 * (`AWAITING_CARRIER_VEHICLE`) en pantalla sería justo el caso en el que alguien tiene que actuar.
 */

const REASONS: ControlTowerBlockerView["reason"][] = [
  "AWAITING_CARRIER_VEHICLE", "VEHICLE_UNAVAILABLE", "DRIVER_UNAVAILABLE",
];

describe("los motivos de bloqueo", () => {
  it("todos tienen etiqueta traducida, ninguno cae al valor crudo", () => {
    for (const reason of REASONS) {
      const label = enumLabel("blockerReason", reason);
      expect(label).not.toEqual(reason);
      expect(label.trim()).not.toEqual("");
    }
  });

  it("distinguen vehículo de conductor: son llamadas de teléfono distintas", () => {
    expect(enumLabel("blockerReason", "VEHICLE_UNAVAILABLE"))
      .not.toEqual(enumLabel("blockerReason", "DRIVER_UNAVAILABLE"));
  });

  /**
   * El detalle se lee y nunca se interpreta. Si alguien lo convirtiera en un switch, un motivo
   * nuevo del backend rompería la pantalla en vez de mostrarse tal cual.
   */
  it("el detalle es texto libre y viaja entero", () => {
    const blocker: ControlTowerBlockerView = {
      tripId: "t-1", tripNumber: 7, shipmentNumber: "SH-00000007",
      reason: "VEHICLE_UNAVAILABLE",
      detail: "Unavailable (MAINTENANCE) until 2026-09-07T19:00Z.",
    };

    expect(blocker.detail).toContain("MAINTENANCE");
  });
});
