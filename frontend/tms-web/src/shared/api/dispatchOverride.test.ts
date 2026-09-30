import { describe, expect, it } from "vitest";
import { ApiError } from "./httpClient";
import {
  DISPATCH_REQUIRES_EXTERNAL_CONFIRMATION, describeDispatchError, dispatchActionFor,
  isExternalConfirmationRequired, normalizeOverrideReason,
} from "./dispatchOverride";
import { describeApiError } from "./problemMessages";

/**
 * El despacho manual frente a la confirmación del almacén (ADR-013 §4, §13.8).
 *
 * Lo que estas pruebas fijan: el override solo se ofrece a quien tiene el permiso, el 409 se
 * reconoce por `code` y nunca por `detail`, y el motivo nunca sale vacío ni pasado de 500.
 */

function problem(status: number, code: string | null, detail?: string): ApiError {
  return new ApiError(status, code === null ? null : { status, code, detail }, "corr-test", `fallback ${status}`);
}

describe("dispatchActionFor", () => {
  it("despacha normal en MANUAL, HYBRID y cuando el modo no se conoce", () => {
    expect(dispatchActionFor("MANUAL", false)).toBe("DIRECT");
    expect(dispatchActionFor("HYBRID", true)).toBe("DIRECT");
    // Sin modo, el servidor decide; su 409 es lo que dispara el override.
    expect(dispatchActionFor(undefined, true)).toBe("DIRECT");
    expect(dispatchActionFor(null, false)).toBe("DIRECT");
  });

  it("en EXTERNAL_REQUIRED ofrece el override solo con el permiso", () => {
    expect(dispatchActionFor("EXTERNAL_REQUIRED", true)).toBe("OVERRIDE");
    expect(dispatchActionFor("EXTERNAL_REQUIRED", false)).toBe("WAIT_FOR_WAREHOUSE");
  });
});

describe("isExternalConfirmationRequired", () => {
  it("reconoce el 409 por su code", () => {
    expect(isExternalConfirmationRequired(problem(409, DISPATCH_REQUIRES_EXTERNAL_CONFIRMATION))).toBe(true);
  });

  it("no confunde un conflicto cualquiera ni un error que no es de la API", () => {
    expect(isExternalConfirmationRequired(problem(409, "conflict", "requires external confirmation"))).toBe(false);
    expect(isExternalConfirmationRequired(new Error(DISPATCH_REQUIRES_EXTERNAL_CONFIRMATION))).toBe(false);
    expect(isExternalConfirmationRequired(null)).toBe(false);
  });
});

describe("normalizeOverrideReason", () => {
  it("recorta y acepta de 1 a 500 caracteres", () => {
    expect(normalizeOverrideReason("  camión esperando en muelle  ")).toBe("camión esperando en muelle");
    expect(normalizeOverrideReason("x".repeat(500))).toHaveLength(500);
  });

  it("rechaza vacío, solo espacios, null y más de 500", () => {
    expect(normalizeOverrideReason("")).toBeNull();
    expect(normalizeOverrideReason("   ")).toBeNull();
    expect(normalizeOverrideReason(null)).toBeNull();
    expect(normalizeOverrideReason("x".repeat(501))).toBeNull();
  });
});

describe("describeDispatchError", () => {
  it("nombra el permiso que falta cuando un override responde 403", () => {
    const message = describeDispatchError(problem(403, "access-denied"), true);
    expect(message).toContain("planning.trip:dispatch-override");
  });

  it("un 403 fuera del override usa la copy genérica de permisos", () => {
    const error = problem(403, "access-denied");
    expect(describeDispatchError(error, false)).toBe(describeApiError(error));
  });

  it("explica el 409 de confirmación externa sin repetir el detail del servidor", () => {
    const message = describeDispatchError(
      problem(409, DISPATCH_REQUIRES_EXTERNAL_CONFIRMATION, "Trip 3 (SH-1) is dispatched by the warehouse"), false,
    );
    expect(message).toContain("almacén");
    expect(message).not.toContain("Trip 3");
  });

  it("conserva el detail de planificación en un conflict", () => {
    const message = describeDispatchError(problem(409, "conflict", "El conductor no está disponible."), false);
    expect(message).toBe("El conductor no está disponible.");
  });
});
