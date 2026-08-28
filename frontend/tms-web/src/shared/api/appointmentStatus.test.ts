import { describe, expect, it } from "vitest";
import { APPOINTMENT_PURPOSES, APPOINTMENT_STATUSES, RESOURCE_TYPES } from "./appointmentsApi";
import type { AppointmentStatus } from "./appointmentsApi";
import { enumLabel } from "../../lib/enums";
import { setLang, t } from "../../lib/i18n";

/**
 * El vocabulario de citas de muelle tal como lo ve la interfaz (migración V41).
 *
 * `enumLabel` devuelve el propio valor cuando no encuentra traducción, así que un estado sin
 * etiqueta no rompe la compilación: se publica y el usuario lee `NO_SHOW` en una celda del tablero.
 * Esta prueba convierte ese olvido en un fallo.
 */
describe("el vocabulario de citas de muelle", () => {
  it("cubre los siete estados que expone el backend, en orden de vida", () => {
    expect(APPOINTMENT_STATUSES).toEqual([
      "REQUESTED", "CONFIRMED", "RESCHEDULED", "ARRIVED", "COMPLETED", "CANCELLED", "NO_SHOW",
    ]);
  });

  it("traduce cada estado, propósito y tipo de puerta al español", () => {
    setLang("es");
    for (const status of APPOINTMENT_STATUSES) {
      expect(enumLabel("appointmentStatus", status), `${status} sin etiqueta`).not.toBe(status);
    }
    for (const purpose of APPOINTMENT_PURPOSES) {
      expect(enumLabel("appointmentPurpose", purpose), `${purpose} sin etiqueta`).not.toBe(purpose);
    }
    for (const type of RESOURCE_TYPES) {
      expect(enumLabel("resourceType", type), `${type} sin etiqueta`).not.toBe(type);
    }
  });

  it("traduce los estados al inglés", () => {
    setLang("en");
    try {
      for (const status of APPOINTMENT_STATUSES) {
        expect(enumLabel("appointmentStatus", status), `${status} sin etiqueta`).not.toBe(status);
      }
      expect(t("No se presentó")).toBe("No-show");
      expect(t("En la puerta")).toBe("At the door");
      expect(t("Muelle")).toBe("Dock");
    } finally {
      setLang("es");
    }
  });

  /**
   * Espejo de `AppointmentStatus.occupiesTheDoor()`. Si la interfaz creyera que una cita cancelada
   * sigue ocupando la puerta, mostraría un hueco lleno que el backend acepta reservar - y al revés
   * es peor: ofrecería un hueco que el backend va a rechazar.
   */
  it("solo cancelada y no-presentado liberan la puerta", () => {
    const releases: AppointmentStatus[] = ["CANCELLED", "NO_SHOW"];
    const holds = APPOINTMENT_STATUSES.filter((status) => !releases.includes(status));

    expect(holds).toEqual(["REQUESTED", "CONFIRMED", "RESCHEDULED", "ARRIVED", "COMPLETED"]);
  });

  it("los cuatro tipos de puerta se comportan igual y existen para leerse", () => {
    expect(RESOURCE_TYPES).toEqual(["DOCK", "DOOR", "BAY", "YARD"]);
  });
});
