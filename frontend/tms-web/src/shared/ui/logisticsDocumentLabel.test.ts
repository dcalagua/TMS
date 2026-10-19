import { afterEach, describe, expect, it } from "vitest";
import { setLang } from "../../lib/i18n";
import * as module from "./logisticsDocumentLabel";

/**
 * ADR-028: la etiqueta de un documento sale de lo que declaró el ERP y del catálogo mínimo de alias
 * (GRE = SUNAT 09/31, guía ecuatoriana = SRI 06). Nada se adivina: sin país, el tipo a secas.
 */

function labels(lang: "es" | "en") {
  setLang(lang);
  return module;
}

afterEach(() => {
  setLang("es");
});

describe("logisticsDocumentLabel", () => {
  it("nombra cada guía por su país y su autoridad", () => {
    const { logisticsDocumentLabel } = labels("es");

    expect(logisticsDocumentLabel("REMISSION_GUIDE", { fiscalCountry: "EC", fiscalDocumentCode: "06" })).toBe("Guía de remisión (SRI 06)");
    // El código de la guía ecuatoriana es el del catálogo aunque el ERP no lo mande.
    expect(logisticsDocumentLabel("REMISSION_GUIDE", { fiscalCountry: "EC" })).toBe("Guía de remisión (SRI 06)");
    expect(logisticsDocumentLabel("GRE_REMITENTE")).toBe("GRE remitente (SUNAT 09)");
    expect(logisticsDocumentLabel("GRE_TRANSPORTISTA", null, true)).toBe("GRE transportista (SUNAT 31)");
    expect(logisticsDocumentLabel("REMISSION_GUIDE", { fiscalCountry: "PE", fiscalDocumentCode: "09" })).toBe("GRE remitente (SUNAT 09)");
    expect(logisticsDocumentLabel("REMISSION_GUIDE", { fiscalCountry: "CO" })).toBe("Guía de remisión (CO)");
    expect(logisticsDocumentLabel("REMISSION_GUIDE")).toBe("Guía de remisión");
  });

  it("los demás tipos llevan la autoridad sólo cuando el ERP la declaró", () => {
    const { logisticsDocumentLabel } = labels("es");

    expect(logisticsDocumentLabel("INVOICE")).toBe("Factura");
    expect(logisticsDocumentLabel("INVOICE", { fiscalCountry: "EC", fiscalDocumentCode: "01" })).toBe("Factura (SRI 01)");
    expect(logisticsDocumentLabel("DELIVERY_NOTE", { fiscalCountry: "EC" })).toBe("Nota de entrega");
  });

  it("la nota dice que el TMS recibe y no emite, con la autoridad del país", () => {
    const { receivedFromErpNote, authorizationLabel } = labels("es");

    expect(receivedFromErpNote("EC")).toBe("Recibido del ERP · el TMS no emite ni valida ante el SRI");
    expect(receivedFromErpNote("PE")).toBe("Recibido del ERP · el TMS no emite ni valida ante la SUNAT");
    expect(receivedFromErpNote(null)).toBe("Recibido del ERP · el TMS no emite ni valida documentos fiscales");
    expect(authorizationLabel("EC")).toBe("Clave de acceso");
    expect(authorizationLabel("PE")).toBe("Autorización");
  });

  it("en inglés", () => {
    const { logisticsDocumentLabel, receivedFromErpNote, authorizationLabel } = labels("en");

    expect(logisticsDocumentLabel("REMISSION_GUIDE", { fiscalCountry: "EC" })).toBe("Remission guide (SRI 06)");
    expect(logisticsDocumentLabel("GRE_REMITENTE")).toBe("Shipper GRE (SUNAT 09)");
    expect(receivedFromErpNote("EC")).toBe("Received from the ERP · TMS neither issues nor validates with the SRI");
    expect(authorizationLabel("EC")).toBe("Access key");
  });
});
