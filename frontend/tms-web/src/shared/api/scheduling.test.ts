import { afterEach, describe, expect, it } from "vitest";
import { ApiError } from "./httpClient";
import {
  ELIGIBILITIES, HOLD_TYPES, SCHEDULING_REASON_CODES, activeFilterCount, isReleasable, needsOverride,
  planBulkRelease, primaryReason, releaseRefusalOf,
  type SchedulingReason, type SchedulingRow,
} from "./schedulingApi";
import { enumLabel } from "../../lib/enums";
import { setLang } from "../../lib/i18n";

/**
 * Las reglas de presentación de Programación y Liberación (ADR-014). El backend juzga cada
 * liberación; estas funciones solo deciden qué se ofrece, qué se envía y cuándo pedir un motivo,
 * y equivocarse en ellas es pedir un motivo que no hacía falta o no pedirlo cuando sí.
 */

const CUTOFF: SchedulingReason = {
  code: "CUTOFF_MISSED", severity: "WARNING", requiresOverride: true, detail: "deadline passed",
};
const NOT_CONFIGURED: SchedulingReason = {
  code: "ROUTE_NOT_CONFIGURED", severity: "WARNING", requiresOverride: false, detail: "no routes",
};
const AMBIGUOUS: SchedulingReason = {
  code: "ROUTE_AMBIGUOUS", severity: "BLOCKED", requiresOverride: false, detail: "R-A, R-B",
};

function row(overrides: Partial<SchedulingRow> = {}): SchedulingRow {
  return {
    orderId: "o-1", orderNumber: "ORD-1", externalReference: null, customerName: "Cliente", customerReference: null,
    originId: "or-1", originCode: "CD", originName: "CD Lima", destinationId: "d-1", destinationCode: "ST",
    destinationName: "Tienda", scheduledDispatchDate: "2026-10-01", priority: "NORMAL", status: "NOT_READY",
    totalWeightKg: 10, totalVolumeM3: 1, totalPallets: 1, version: 0, eligibility: "ELIGIBLE",
    requiresOverride: false, reasons: [], releaseDeadline: null, releaseDeadlineEndOfDay: false,
    routeResolution: "RESOLVED", routeCode: "R-1", routeName: "Ruta 1", routeCandidates: ["R-1"],
    locationFrequencyCode: null, routeFrequencyCode: null, activeHolds: 0, activeBlockingHolds: 0,
    ...overrides,
  };
}

afterEach(() => setLang("es"));

describe("releaseRefusalOf", () => {
  it("lee los motivos del 409 de la liberación, por sus campos y no por la frase", () => {
    const error = new ApiError(409, {
      code: "conflict", detail: "Order ORD-1 can only be released with an override reason.",
      ...({ eligibility: "WARNING", overrideRequired: true, reasons: [CUTOFF] } as object),
    }, "corr", "conflict");

    expect(releaseRefusalOf(error)).toEqual({ eligibility: "WARNING", overrideRequired: true, reasons: [CUTOFF] });
  });

  it("no confunde cualquier otro conflicto con un rechazo de liberación", () => {
    expect(releaseRefusalOf(new ApiError(409, { code: "conflict", detail: "stale version" }, "c", "x"))).toBeNull();
    expect(releaseRefusalOf(new ApiError(400, { code: "malformed-request" }, "c", "x"))).toBeNull();
    expect(releaseRefusalOf(new Error("boom"))).toBeNull();
  });
});

describe("cuándo pedir un motivo", () => {
  it("lo pide para un aviso que lo requiere y no para el informativo ROUTE_NOT_CONFIGURED", () => {
    expect(needsOverride(row({ eligibility: "WARNING", reasons: [CUTOFF] }))).toBe(true);
    expect(needsOverride(row({ eligibility: "WARNING", reasons: [NOT_CONFIGURED] }))).toBe(false);
    expect(needsOverride(row())).toBe(false);
  });

  it("solo ofrece liberar lo pendiente y no bloqueado", () => {
    expect(isReleasable(row())).toBe(true);
    expect(isReleasable(row({ eligibility: "WARNING", reasons: [CUTOFF] }))).toBe(true);
    expect(isReleasable(row({ eligibility: "BLOCKED", reasons: [AMBIGUOUS] }))).toBe(false);
    expect(isReleasable(row({ status: "READY_FOR_PLANNING" }))).toBe(false);
  });
});

describe("planBulkRelease", () => {
  it("descarta de entrada lo bloqueado o ya liberado y pide motivo solo si alguna enviada lo necesita", () => {
    const eligible = row({ orderId: "a" });
    const blocked = row({ orderId: "b", eligibility: "BLOCKED", reasons: [AMBIGUOUS] });
    const released = row({ orderId: "c", status: "READY_FOR_PLANNING" });
    const informative = row({ orderId: "d", eligibility: "WARNING", reasons: [NOT_CONFIGURED] });

    const plan = planBulkRelease([eligible, blocked, released, informative]);
    expect(plan.toSend.map((candidate) => candidate.orderId)).toEqual(["a", "d"]);
    expect(plan.skipped.map((candidate) => candidate.orderId)).toEqual(["b", "c"]);
    expect(plan.reasonNeeded).toBe(false);

    const late = row({ orderId: "e", eligibility: "WARNING", reasons: [CUTOFF] });
    expect(planBulkRelease([eligible, late]).reasonNeeded).toBe(true);
  });
});

describe("primaryReason", () => {
  it("enseña primero lo que bloquea, después lo que pide motivo", () => {
    expect(primaryReason(row({ reasons: [NOT_CONFIGURED, CUTOFF, AMBIGUOUS] }))).toBe(AMBIGUOUS);
    expect(primaryReason(row({ reasons: [NOT_CONFIGURED, CUTOFF] }))).toBe(CUTOFF);
    expect(primaryReason(row({ reasons: [NOT_CONFIGURED] }))).toBe(NOT_CONFIGURED);
    expect(primaryReason(row())).toBeNull();
  });
});

describe("activeFilterCount", () => {
  it("cuenta los filtros puestos, incluido un 'sin retención' que es false", () => {
    expect(activeFilterCount({})).toBe(0);
    expect(activeFilterCount({ originId: "x", hasHold: false, customer: "" })).toBe(2);
  });
});

describe("el vocabulario de programación", () => {
  it("tiene etiqueta en español y en inglés para cada código que expone el backend", () => {
    for (const lang of ["es", "en"] as const) {
      setLang(lang);
      for (const code of SCHEDULING_REASON_CODES) {
        expect(enumLabel("schedulingReason", code), code).not.toBe(code);
      }
      for (const eligibility of ELIGIBILITIES) {
        expect(enumLabel("eligibility", eligibility), eligibility).not.toBe(eligibility);
      }
      for (const type of HOLD_TYPES) {
        expect(enumLabel("holdType", type), type).not.toBe(type);
      }
      expect(enumLabel("advisoryType", "ORDER_HOLD_ON_COMMITTED_TRIP")).not.toBe("ORDER_HOLD_ON_COMMITTED_TRIP");
    }
    setLang("en");
    expect(enumLabel("schedulingReason", "CUTOFF_MISSED")).toBe("Cutoff missed");
  });
});
