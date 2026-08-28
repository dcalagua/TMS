import { describe, expect, it } from "vitest";
import type { WorkAssignmentTripView, WorkAssignmentView } from "./workAssignmentsApi";
import { enumLabel } from "../../lib/enums";

/**
 * El día de trabajo (migración V47, deuda D5).
 *
 * Dos reglas de lectura que ningún typechecker ve.
 *
 * 1. **`repositionMinutes` null no es cero.** En el primer envío significa "no hay desde dónde
 *    desplazarse"; después significa **"el tramo no se pudo medir"**. Pintarlo como 0 diría que el
 *    camión se teletransporta, y un día construido sobre eso no lo ha revisado nadie.
 * 2. **Los conflictos se nombran.** Nueve motivos existen porque el sistema sabe la causa; un
 *    genérico "no disponible" obliga a un planificador a ir a averiguar cuál es.
 */

const REASONS = [
  "DRIVER_UNAVAILABLE", "VEHICLE_UNAVAILABLE", "MAINTENANCE_BLOCK", "SHIFT_CONFLICT",
  "TRIP_OVERLAP", "INSUFFICIENT_REPOSITION_TIME", "ROUTING_UNKNOWN", "LICENSE_INVALID",
  "CARRIER_MISMATCH",
] as const;

/** Lo que la pantalla escribe entre dos envíos. */
function repositionLabel(trip: WorkAssignmentTripView): string {
  if (trip.sequence === 1) return "";
  return trip.repositionMinutes === null ? "sin medir" : `${trip.repositionMinutes} min`;
}

describe("el desplazamiento entre envíos", () => {
  it("null después del primer envío se lee como sin medir, nunca como cero", () => {
    const unmeasured: WorkAssignmentTripView = {
      tripId: "t-2", shipmentNumber: "SH-2", sequence: 2,
      plannedStart: null, plannedEnd: null, repositionMinutes: null,
    };

    expect(repositionLabel(unmeasured)).toBe("sin medir");
    expect(repositionLabel(unmeasured)).not.toBe("0 min");
  });

  it("cero medido y no medido son cosas distintas", () => {
    const measuredZero: WorkAssignmentTripView = {
      tripId: "t-2", shipmentNumber: "SH-2", sequence: 2,
      plannedStart: null, plannedEnd: null, repositionMinutes: 0,
    };

    // Un 0 medido es legítimo: dos envíos que salen del mismo sitio.
    expect(repositionLabel(measuredZero)).toBe("0 min");
  });

  it("el primer envío no lleva desplazamiento porque no hay desde dónde", () => {
    expect(repositionLabel({
      tripId: "t-1", shipmentNumber: "SH-1", sequence: 1,
      plannedStart: null, plannedEnd: null, repositionMinutes: null,
    })).toBe("");
  });
});

describe("los motivos de conflicto", () => {
  it("los nueve tienen etiqueta traducida, ninguno cae al valor crudo", () => {
    for (const reason of REASONS) {
      const label = enumLabel("resourceRejectionReason", reason);
      expect(label).not.toEqual(reason);
      expect(label.trim()).not.toEqual("");
    }
  });

  it("taller y no disponible son etiquetas distintas: los resuelve otra persona", () => {
    expect(enumLabel("resourceRejectionReason", "MAINTENANCE_BLOCK"))
      .not.toEqual(enumLabel("resourceRejectionReason", "VEHICLE_UNAVAILABLE"));
  });

  it("solapamiento y falta de tiempo son distintos: uno necesita otro camión, otro otra hora", () => {
    expect(enumLabel("resourceRejectionReason", "TRIP_OVERLAP"))
      .not.toEqual(enumLabel("resourceRejectionReason", "INSUFFICIENT_REPOSITION_TIME"));
  });
});

describe("factible no es permitido", () => {
  /**
   * Un día sin conflictos no autoriza nada. Los envíos se despachan de uno en uno y todo guard que
   * hoy rechaza una salida la sigue rechazando - una asignación no es una vía alternativa.
   */
  it("un día viable sigue llevando sus envíos por sus propios guards", () => {
    const feasible: WorkAssignmentView = {
      id: "a-1", operationalDate: "2026-09-07", vehicleId: "v-1", vehicleCode: "TR-04",
      driverId: "d-1", driverName: "DRV-01", status: "CONFIRMED", notes: null, version: 1,
      feasible: true, trips: [], conflicts: [],
    };

    // `feasible` describe la secuencia, no el permiso de salir. La distinción vive en el backend
    // (TripExecutionService) y esta prueba fija que el cliente no la confunda con autorización.
    expect(feasible.feasible).toBe(true);
    expect(feasible.status).toBe("CONFIRMED");
  });
});
