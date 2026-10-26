import { afterEach, describe, expect, it } from "vitest";
import type { LocationView } from "../../shared/api/locationsApi";
import { setLang } from "../../lib/i18n";
import { appointmentSites, hoursFromRows, rowError, rowsFromHours, summarizeHours } from "./dockCalendar";

/**
 * Las reglas puras del horario de una puerta (V41) y del selector de sitio de las citas.
 *
 * Lo que se protege: que un horario vacío se lea como «abierta siempre» (lo que el backend
 * entiende) y no como cerrada; que el editor no deje mandar una franja que el servidor rechaza; y
 * que una planta que sólo es ORIGEN aparezca en el selector — antes sólo salían destinos.
 */

afterEach(() => setLang("es"));

const MON_SAT = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"] as const;

describe("el resumen del horario", () => {
  it("sin entradas dice que la puerta está abierta siempre, no cerrada", () => {
    expect(summarizeHours([])).toBe("Sin horario: abierta siempre");
  });

  it("agrupa los días seguidos con la misma franja", () => {
    const hours = MON_SAT.map((day) => ({ day, opensAt: "07:00:00", closesAt: "19:00:00" }));
    expect(summarizeHours(hours)).toBe("Lun–Sáb 07:00–19:00");
  });

  it("separa los días con otra franja o no seguidos", () => {
    expect(summarizeHours([
      { day: "MONDAY", opensAt: "07:00", closesAt: "19:00" },
      { day: "TUESDAY", opensAt: "07:00", closesAt: "19:00" },
      { day: "SATURDAY", opensAt: "08:00", closesAt: "12:00" },
    ])).toBe("Lun–Mar 07:00–19:00 · Sáb 08:00–12:00");
  });

  it("se lee en inglés", () => {
    setLang("en");
    expect(summarizeHours([])).toBe("No hours: always open");
  });
});

describe("el editor de la semana", () => {
  it("parte de lo guardado y deja cerrados, con hora sugerida, los días sin entrada", () => {
    const rows = rowsFromHours([{ day: "MONDAY", opensAt: "06:30:00", closesAt: "14:00:00" }]);
    expect(rows).toHaveLength(7);
    expect(rows[0]).toEqual({ day: "MONDAY", open: true, opensAt: "06:30", closesAt: "14:00" });
    expect(rows[6]).toEqual({ day: "SUNDAY", open: false, opensAt: "07:00", closesAt: "19:00" });
  });

  it("envía sólo los días abiertos", () => {
    const rows = rowsFromHours([]).map((row) => ({ ...row, open: row.day !== "SUNDAY" }));
    expect(hoursFromRows(rows).map((h) => h.day)).toEqual([...MON_SAT]);
    expect(hoursFromRows(rows)[0]).toEqual({ day: "MONDAY", opensAt: "07:00", closesAt: "19:00" });
  });

  it("rechaza antes de enviar una franja que cierra antes de abrir, y no se queja de un día cerrado", () => {
    expect(rowError({ day: "MONDAY", open: true, opensAt: "22:00", closesAt: "06:00" })).toMatch(/Un turno nocturno son dos días/);
    expect(rowError({ day: "MONDAY", open: true, opensAt: "", closesAt: "06:00" })).toMatch(/apertura/);
    expect(rowError({ day: "MONDAY", open: false, opensAt: "22:00", closesAt: "06:00" })).toBeNull();
    expect(rowError({ day: "MONDAY", open: true, opensAt: "07:00", closesAt: "19:00" })).toBeNull();
  });
});

function location(code: string, roles: LocationView["roles"]): LocationView {
  return { id: `id-${code}`, code, name: `${code} nombre`, roles } as LocationView;
}

describe("los sitios de las citas", () => {
  it("lista orígenes y destinos, orígenes primero, con su uso", () => {
    const sites = appointmentSites([
      location("CLI-SUP-01", ["DESTINATION"]),
      location("GYE-CD", ["ORIGIN"]),
      location("SIN-USO", []),
      location("CLI-AGR-01", ["DESTINATION"]),
    ]);
    expect(sites.map((s) => s.id)).toEqual(["id-GYE-CD", "id-CLI-AGR-01", "id-CLI-SUP-01"]);
    expect(sites[0].label).toBe("GYE-CD · GYE-CD nombre (Origen)");
    expect(sites[1].label).toContain("(Destino)");
  });
});
