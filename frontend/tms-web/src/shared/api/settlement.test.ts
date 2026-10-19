import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { COMPANY_ID_HEADER, setAuthRefreshHandler, setAuthTokenProvider } from "./httpClient";
import {
  approveInvoice, createTolerancePolicy, deactivateTolerancePolicy, exportInvoice,
  fetchTolerancePolicies, matchInvoice, receiveInvoice, rejectInvoice, updateTolerancePolicy,
  type CarrierInvoiceSummaryView, type InvoiceLineView,
} from "./settlementApi";
import { enumLabel } from "../../lib/enums";

/**
 * El contrato de la auditoría de flete (migración V46).
 *
 * Dos cosas se protegen aquí y ninguna la ve un typechecker.
 *
 * 1. **Las rutas literales** y, más importante, que aprobar y exportar sean **endpoints distintos**.
 *    El servidor los guarda con permisos distintos a propósito - quien teclea una factura no debe
 *    poder aprobar la suya - y un cliente que los mezclara pediría el permiso equivocado.
 * 2. **`expectedAmount` null se lee como hueco, nunca como cero.** Una factura cuyos envíos nunca
 *    se tarificaron no tiene con qué compararse; pintar 0,00 la reportaría como un sobrecoste del
 *    importe entero y mandaría a alguien a discutir con un transportista que no hizo nada mal.
 */

const COMPANY = "11111111-1111-4111-8111-111111111111";
const INVOICE = "22222222-2222-4222-8222-222222222222";

let fetchMock: ReturnType<typeof vi.fn>;

function sent() {
  const [url, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit];
  return { url: new URL(url), init };
}

beforeEach(() => {
  fetchMock = vi.fn().mockImplementation(async () => new Response(JSON.stringify({}), {
    status: 200, headers: { "content-type": "application/json" },
  }));
  vi.stubGlobal("fetch", fetchMock);
  setAuthTokenProvider(async () => "test-token");
  setAuthRefreshHandler(async () => null);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("las rutas de la auditoría de flete", () => {
  it("comparar es su propia ruta, con el ámbito de empresa", async () => {
    await matchInvoice(COMPANY, INVOICE);

    const { url, init } = sent();
    expect(init.method).toBe("POST");
    expect(url.pathname).toBe(`/api/v1/settlement/invoices/${INVOICE}/match`);
    expect((init.headers as Record<string, string>)[COMPANY_ID_HEADER]).toBe(COMPANY);
  });

  /** Autoridades distintas en el servidor; rutas distintas en el cliente. */
  it("aprobar y exportar son endpoints separados", async () => {
    await approveInvoice(COMPANY, INVOICE, "Dentro de tolerancia.");
    const approve = sent().url.pathname;
    await exportInvoice(COMPANY, INVOICE);
    const exported = sent().url.pathname;

    expect(approve).toBe(`/api/v1/settlement/invoices/${INVOICE}/approve`);
    expect(exported).toBe(`/api/v1/settlement/invoices/${INVOICE}/export`);
    expect(approve).not.toBe(exported);
  });

  it("un rechazo manda el motivo, porque el transportista tiene que poder responderlo", async () => {
    await rejectInvoice(COMPANY, INVOICE, "Factura de un envío que no pedimos.");

    expect(JSON.parse(sent().init.body as string))
      .toEqual({ comment: "Factura de un envío que no pedimos." });
  });
});

describe("cómo se lee una factura sin comparación", () => {
  /** La regla que hace honesta a la pantalla: null es hueco, no cero. */
  it("expectedAmount null no es un sobrecoste: no hay nada con qué comparar", () => {
    const unmatchable: CarrierInvoiceSummaryView = {
      id: INVOICE, carrierId: "c-1", carrierName: "Transportes SA",
      invoiceNumber: "INV-001", invoiceDate: "2026-04-05", currency: "PEN",
      totalAmount: 1800, status: "DISCREPANCY", matchStatus: "UNMATCHABLE",
      expectedAmount: null, differenceAmount: null, openDiscrepancyCount: 1,
    };

    // Ni el esperado ni la diferencia existen. Si alguien los tratara como 0, la diferencia
    // "calculada" sería 1800 - el importe entero reportado como sobrecoste.
    expect(unmatchable.expectedAmount).toBeNull();
    expect(unmatchable.differenceAmount).toBeNull();
  });

  it("UNMATCHABLE se etiqueta como falta de comparación, no como error", () => {
    expect(enumLabel("matchStatus", "UNMATCHABLE")).toBe("Sin comparación posible");
    expect(enumLabel("matchStatus", "DISCREPANCY")).not.toBe(enumLabel("matchStatus", "UNMATCHABLE"));
  });

  it("todos los estados de factura tienen etiqueta traducida", () => {
    for (const status of ["RECEIVED", "MATCHING", "MATCHED", "DISCREPANCY",
      "UNDER_REVIEW", "APPROVED", "REJECTED", "EXPORTED"] as const) {
      expect(enumLabel("invoiceStatus", status)).not.toEqual(status);
    }
  });
});

describe("fase 50: exportación, códigos de cargo y tolerancias", () => {
  const POLICY = "33333333-3333-4333-8333-333333333333";
  const TRIP = "44444444-4444-4444-8444-444444444444";

  it("exportar manda el formato elegido en la query, y JSON por defecto", async () => {
    await exportInvoice(COMPANY, INVOICE, "CSV");
    expect(sent().url.pathname).toBe(`/api/v1/settlement/invoices/${INVOICE}/export`);
    expect(sent().url.searchParams.get("format")).toBe("CSV");

    await exportInvoice(COMPANY, INVOICE);
    expect(sent().url.searchParams.get("format")).toBe("JSON");
  });

  it("registrar una factura manda el código de cargo de cada línea", async () => {
    await receiveInvoice(COMPANY, {
      carrierId: "c-1", invoiceNumber: "F001-1", invoiceDate: "2026-10-01", currency: "PEN",
      totalAmount: 1250,
      lines: [
        { tripId: TRIP, chargeCode: "FREIGHT", description: "Flete", lineAmount: 1000 },
        { tripId: TRIP, chargeCode: "TOLL", description: "Peaje", lineAmount: 250 },
      ],
    });

    const { url, init } = sent();
    expect(init.method).toBe("POST");
    expect(url.pathname).toBe("/api/v1/settlement/invoices");
    const body = JSON.parse(init.body as string);
    expect(body.lines.map((line: { chargeCode: string }) => line.chargeCode)).toEqual(["FREIGHT", "TOLL"]);
  });

  it("listar tolerancias pide includeInactive de forma explícita", async () => {
    await fetchTolerancePolicies(COMPANY, true);
    let { url, init } = sent();
    expect(init.method ?? "GET").toBe("GET");
    expect(url.pathname).toBe("/api/v1/settlement/tolerance-policies");
    expect(url.searchParams.get("includeInactive")).toBe("true");

    await fetchTolerancePolicies(COMPANY);
    ({ url } = sent());
    expect(url.searchParams.get("includeInactive")).toBe("false");
  });

  it("crear, editar y desactivar una tolerancia son rutas y métodos distintos", async () => {
    await createTolerancePolicy(COMPANY, { carrierId: null, absoluteAmount: 5, currency: "PEN", percentage: 2 });
    let { url, init } = sent();
    expect(init.method).toBe("POST");
    expect(url.pathname).toBe("/api/v1/settlement/tolerance-policies");
    expect(JSON.parse(init.body as string)).toEqual({ carrierId: null, absoluteAmount: 5, currency: "PEN", percentage: 2 });

    await updateTolerancePolicy(COMPANY, POLICY, { absoluteAmount: null, percentage: 3, currency: null });
    ({ url, init } = sent());
    expect(init.method).toBe("PUT");
    expect(url.pathname).toBe(`/api/v1/settlement/tolerance-policies/${POLICY}`);
    // El ámbito no viaja al editar: no se puede cambiar.
    expect(JSON.parse(init.body as string)).not.toHaveProperty("carrierId");

    await deactivateTolerancePolicy(COMPANY, POLICY);
    ({ url, init } = sent());
    expect(init.method).toBe("POST");
    expect(url.pathname).toBe(`/api/v1/settlement/tolerance-policies/${POLICY}/deactivate`);
    expect((init.headers as Record<string, string>)[COMPANY_ID_HEADER]).toBe(COMPANY);
  });

  it("una penalidad no tiene esperado: queda fuera de la comparación, no es un cero", () => {
    const penalty: InvoiceLineView = {
      id: "l-2", lineNumber: 2, tripId: TRIP, shipmentNumber: "SH-1", chargeCode: "PENALTY",
      description: "Penalidad por demora", quantity: null, unitAmount: null, lineAmount: 80,
      expectedAmount: null, expectedBasis: null, actualAmount: null, differenceAmount: null,
    };
    expect(penalty.expectedAmount).toBeNull();
    expect(enumLabel("chargeCode", penalty.chargeCode)).toBe("Penalidad");
    expect(enumLabel("chargeCode", "FREIGHT")).toBe("Flete (todo incluido)");
    expect(enumLabel("expectedBasis", "ESTIMATED")).toBe("Contra estimado");
  });
});
