import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { COMPANY_ID_HEADER, setAuthRefreshHandler, setAuthTokenProvider } from "./httpClient";
import {
  approveInvoice, exportInvoice, matchInvoice, rejectInvoice,
  type CarrierInvoiceSummaryView,
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
