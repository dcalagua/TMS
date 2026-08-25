import { describe, expect, it } from "vitest";
import { ApiError } from "./httpClient";
import {
  describeApiError,
  describeImportError,
  describePlanningError,
  isAuthProblem,
  isCompanyScopeStale,
} from "./problemMessages";

/**
 * La traducción de un documento Problem Details (RFC 9457) a lo que lee una persona.
 *
 * La regla que estas pruebas fijan es la de API_CONVENTIONS §4: se ramifica por `code`, que es
 * estable, y nunca por `detail`, que es prosa y puede reescribirse en el servidor sin avisar.
 * Un cambio que invierta eso hace que la copy de la UI dependa de un texto de logs.
 */

function problem(status: number, code: string | null, detail?: string): ApiError {
  return new ApiError(
    status,
    code === null ? null : { status, code, detail },
    "corr-test",
    `fallback ${status}`,
  );
}

describe("describeApiError", () => {
  it("da copy propia a cada código conocido, sin repetir el genérico", () => {
    const codes = [
      "unauthenticated",
      "access-denied",
      "validation-failed",
      "resource-not-found",
      "conflict",
      "company-scope-required",
      "feature-not-configured",
      "internal-error",
    ];

    const messages = codes.map((code) => describeApiError(problem(400, code)));

    expect(new Set(messages).size).toBe(codes.length);
    for (const message of messages) {
      expect(message.length).toBeGreaterThan(0);
    }
  });

  it("no filtra el detail del backend en la copy genérica", () => {
    const detail = "constraint uq_location_code violated on tms.location";

    const message = describeApiError(problem(500, "internal-error", detail));

    // Un mensaje de PostgreSQL no es texto para un planificador.
    expect(message).not.toContain("constraint");
    expect(message).not.toContain("tms.location");
  });

  it("cae en un mensaje genérico ante un código que esta versión del frontend no conoce", () => {
    const message = describeApiError(problem(418, "some-future-code"));

    expect(message.length).toBeGreaterThan(0);
    expect(message).not.toContain("some-future-code");
  });

  it("cae en un mensaje genérico cuando la respuesta no traía documento", () => {
    expect(describeApiError(problem(502, null)).length).toBeGreaterThan(0);
  });
});

describe("clasificadores de problema", () => {
  it("reconoce los fallos de sesión y solo esos", () => {
    expect(isAuthProblem(problem(401, "invalid-token"))).toBe(true);
    expect(isAuthProblem(problem(401, "unauthenticated"))).toBe(true);
    expect(isAuthProblem(problem(403, "access-denied"))).toBe(false);
    expect(isAuthProblem(problem(404, "resource-not-found"))).toBe(false);
  });

  it("reconoce solo el código que obliga a recargar la empresa elegida", () => {
    expect(isCompanyScopeStale(problem(403, "company-scope-forbidden"))).toBe(true);
    expect(isCompanyScopeStale(problem(400, "company-scope-invalid"))).toBe(false);
    expect(isCompanyScopeStale(problem(403, "access-denied"))).toBe(false);
  });
});

describe("familias que sí muestran el detail del backend", () => {
  it("planificación muestra el detail, que es donde se nombran las dimensiones que no cupieron", () => {
    const detail = "No cabe: weight 1500 kg sobre un límite de 1000 kg.";

    expect(describePlanningError(problem(409, "conflict", detail))).toContain("weight 1500");
  });

  it("importación muestra el detail, que nombra la fila y la columna rechazadas", () => {
    const detail = "Fila 3, columna businessName: es obligatoria.";

    expect(describeImportError(problem(400, "malformed-request", detail))).toContain("businessName");
  });

  it("pero un fallo de sesión dentro de planificación sigue usando la copy de sesión", () => {
    const message = describePlanningError(problem(401, "unauthenticated", "JWT expired at ..."));

    expect(message).not.toContain("JWT");
  });
});
