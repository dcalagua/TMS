import { describe, expect, it } from "vitest";
import { EN_INTEGRATION_DOCS } from "../../lib/i18n.integrationDocs";
import {
  COMMON_ERRORS, EVENT_DOCS, SCOPE_DOCS, WEBHOOK_RESPONSE_RULES,
  integrationBaseUrl, orderedByCatalogue, pingExchange, renderRequest, type DocExchange,
} from "./integrationDocs";

/**
 * Los alcances de `IntegrationScope` y los eventos de `WebhookEventType` del backend, copiados a mano.
 * Si el backend añade uno, el panel lo muestra igual («sin ejemplo documentado»); este test es el que
 * avisa de que falta escribir su ejemplo.
 */
const BACKEND_SCOPES = [
  "integration.location:write",
  "integration.order:write",
  "integration.shipment:read",
  "integration.tracking:write",
  "integration.tender:respond",
  "integration.dispatch:write",
  "integration.warehouse-milestone:write",
  "integration.document:write",
  "integration.material:write",
  "integration.order:read",
  "integration.warehouse-order-event:write",
  "integration.payable-payment:write",
];

const BACKEND_EVENTS = [
  "SHIPMENT_CONFIRMED", "SHIPMENT_READY", "SHIPMENT_DISPATCHED", "SHIPMENT_COMPLETED", "SHIPMENT_CANCELLED",
  "SHIPMENT_CHANGED", "DELIVERY_RESULT_RECORDED",
  "TENDER_SENT", "TENDER_ACCEPTED", "TENDER_REJECTED", "TENDER_EXPIRED", "TENDER_CANCELLED",
  "ORDER_CANCELLED", "INVOICE_APPROVED", "PAYABLE_EXPORTED", "PAYABLE_PAID",
];

function exchangeTexts(exchange: DocExchange): string[] {
  return [exchange.title, ...(exchange.notes ?? []), ...exchange.responses.map((response) => response.label)];
}

describe("catálogo de Documentación de integraciones", () => {
  it("documenta cada alcance y cada evento del backend, en su orden", () => {
    expect(SCOPE_DOCS.map((doc) => doc.code)).toEqual(BACKEND_SCOPES);
    expect(EVENT_DOCS.map((doc) => doc.type)).toEqual(BACKEND_EVENTS);
  });

  it("cada operación tiene una ruta bajo la base y al menos una respuesta", () => {
    const exchanges = [
      ...SCOPE_DOCS.flatMap((doc) => doc.exchanges),
      ...EVENT_DOCS.flatMap((doc) => (doc.followUp ? [doc.followUp.exchange] : [])),
    ];
    for (const exchange of exchanges) {
      expect(exchange.path.startsWith("/"), exchange.key).toBe(true);
      expect(exchange.responses.length, exchange.key).toBeGreaterThan(0);
      // Un GET no lleva cuerpo; un POST/PUT sí.
      expect(exchange.body === undefined, exchange.key).toBe(exchange.method === "GET");
    }
  });

  it("el seguimiento de un evento pide un alcance que existe", () => {
    for (const doc of EVENT_DOCS) {
      if (doc.followUp) expect(BACKEND_SCOPES).toContain(doc.followUp.scope);
    }
  });

  it("cada texto del catálogo tiene su traducción al inglés", () => {
    const texts = [
      ...SCOPE_DOCS.flatMap((doc) => [doc.summary, ...doc.exchanges.flatMap(exchangeTexts)]),
      ...EVENT_DOCS.flatMap((doc) => [
        doc.summary, ...(doc.notes ?? []), ...(doc.followUp ? exchangeTexts(doc.followUp.exchange) : []),
      ]),
      ...COMMON_ERRORS.map((error) => error.label),
      ...WEBHOOK_RESPONSE_RULES.flatMap((rule) => [rule.outcome]),
      ...exchangeTexts(pingExchange("tmsc_x", "x", [])),
    ];
    const missing = [...new Set(texts)].filter((text) => !(text in EN_INTEGRATION_DOCS));
    expect(missing).toEqual([]);
  });
});

describe("integrationBaseUrl", () => {
  it("cambia /api/v1 por /integration/v1 en la misma máquina", () => {
    expect(integrationBaseUrl("http://localhost:8080/api/v1")).toBe("http://localhost:8080/integration/v1");
    expect(integrationBaseUrl("https://tms.example.com/api/v1/")).toBe("https://tms.example.com/integration/v1");
  });

  it("completa una base relativa con el origen de la página", () => {
    expect(integrationBaseUrl("/api/v1")).toBe(`${window.location.origin}/integration/v1`);
  });
});

describe("orderedByCatalogue", () => {
  it("ordena como el catálogo y deja al final lo que no conoce", () => {
    expect(orderedByCatalogue(["c", "zz", "a"], ["a", "b", "c"])).toEqual(["a", "c", "zz"]);
  });
});

describe("renderRequest", () => {
  it("escribe la línea, las cabeceras y el cuerpo como viajan", () => {
    expect(renderRequest("POST", "https://h/integration/v1/orders", { Authorization: "Bearer x" }, { a: 1 }))
      .toBe('POST https://h/integration/v1/orders\nAuthorization: Bearer x\n\n{\n  "a": 1\n}');
    expect(renderRequest("GET", "https://h/integration/v1/ping", { Authorization: "Bearer x" }))
      .toBe("GET https://h/integration/v1/ping\nAuthorization: Bearer x");
  });
});
