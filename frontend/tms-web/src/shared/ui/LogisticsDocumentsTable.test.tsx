import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { LogisticsDocumentView } from "../api/logisticsDocumentsApi";
import { LogisticsDocumentsTable } from "./LogisticsDocumentsTable";

/**
 * Los documentos del ERP (ADR-015): el número tal cual se emitió, el tipo en español y el
 * importe ausente como hueco, nunca como cero. ADR-028: la etiqueta por país y el detalle fiscal
 * de una guía de remisión ecuatoriana, con la nota de que el TMS no emite ni valida ante el SRI.
 */

const CLAVE = "1510202606099299999900110010010000001231234567819";

function document(overrides: Partial<LogisticsDocumentView> = {}): LogisticsDocumentView {
  return {
    id: "doc-1",
    sourceSystem: "SAPB1_PE",
    documentType: "GRE_REMITENTE",
    documentNumber: "T001-00001234",
    issueDate: "2026-09-27",
    recipientName: "Bodega Central SAC",
    amount: null,
    currency: null,
    externalStatus: "ACEPTADA",
    receivedAt: "2026-09-27T10:00:00Z",
    ...overrides,
  };
}

/** La guía de PLAPASA del ejemplo del contrato (números ficticios y bien formados). */
function ecuadorianGuide(): LogisticsDocumentView {
  return document({
    id: "doc-ec",
    sourceSystem: "SAP_EC",
    documentType: "REMISSION_GUIDE",
    documentNumber: "001-001-000000123",
    issueDate: "2026-10-15",
    recipientName: null,
    externalStatus: "AUTORIZADO",
    issuerTaxId: "0992999999001",
    fiscal: {
      fiscalCountry: "EC", fiscalDocumentCode: "06", authorizationNumber: CLAVE,
      transportStartDate: "2026-10-15", transportEndDate: "2026-10-16",
      carrierTaxId: "0991111111001", carrierName: "TRLIT Transportes del Litoral (demo)", vehiclePlate: "GBC-5678",
      originAddress: "Km 14,5 Vía a Daule, Guayaquil", transferReason: null,
    },
    recipients: [{
      taxId: "0990000000001", name: "Ferretería El Constructor", destinationAddress: "Av. Juan Tanca Marengo Km 2, Guayaquil",
      transferReason: "Venta", destinationEstablishmentCode: null, route: null, relatedDocumentCode: "01",
      relatedDocumentNumber: "001-002-000004567", relatedDocumentAuthorizationNumber: null,
      relatedDocumentDate: "2026-10-14",
      items: [{ code: "PVC-110-3", additionalCode: null, description: "Tubo PVC 110 mm x 3 m", quantity: 120, unit: "UND" }],
    }],
  });
}

describe("la tabla de documentos logísticos", () => {
  it("enseña el tipo por país y el número tal cual", () => {
    render(<LogisticsDocumentsTable documents={[document()]} />);

    expect(screen.getByText("GRE remitente (SUNAT 09)")).toBeInTheDocument();
    expect(screen.getByText("T001-00001234")).toBeInTheDocument();
    expect(screen.getByText("ACEPTADA")).toBeInTheDocument();
  });

  it("no inventa un importe cero cuando el ERP no lo mandó", () => {
    render(<LogisticsDocumentsTable documents={[document()]} />);

    expect(screen.queryByText(/0[.,]00/)).not.toBeInTheDocument();
  });

  it("añade el pedido en la vista por viaje y tolera un tipo desconocido", () => {
    render(<LogisticsDocumentsTable showOrder documents={[{ ...document({ documentType: "CREDIT_NOTE" }), orderLabel: "TO-00000011" }]} />);

    expect(screen.getByText("TO-00000011")).toBeInTheDocument();
    expect(screen.getByText("CREDIT_NOTE")).toBeInTheDocument();
  });

  it("un documento recibido antes de V69, sin datos fiscales, no ofrece detalle", () => {
    render(<LogisticsDocumentsTable documents={[document({ documentType: "INVOICE" })]} />);

    expect(screen.getByText("Factura")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Ver detalle/ })).not.toBeInTheDocument();
  });

  it("la guía ecuatoriana se nombra SRI 06 y su fila resume transportista y placa", () => {
    render(<LogisticsDocumentsTable documents={[ecuadorianGuide()]} />);

    expect(screen.getByText("Guía de remisión (SRI 06)")).toBeInTheDocument();
    expect(screen.getByText("TRLIT Transportes del Litoral (demo) · GBC-5678")).toBeInTheDocument();
    // Sin recipientName en la cabecera, se enseña el primer destinatario.
    expect(screen.getByText("Ferretería El Constructor")).toBeInTheDocument();
  });

  it("al desplegarla enseña la clave de acceso copiable, el traslado, los destinatarios y la nota del SRI", async () => {
    const user = userEvent.setup();
    render(<LogisticsDocumentsTable documents={[ecuadorianGuide()]} />);

    await user.click(screen.getByRole("button", { name: "Ver detalle de Guía de remisión (SRI 06) 001-001-000000123" }));

    expect(screen.getByText("Recibido del ERP · el TMS no emite ni valida ante el SRI")).toBeInTheDocument();
    expect(screen.getByText("Clave de acceso")).toBeInTheDocument();
    expect(screen.getByText(CLAVE)).toBeInTheDocument();
    expect(screen.getByText("0992999999001")).toBeInTheDocument();
    expect(screen.getByText("Km 14,5 Vía a Daule, Guayaquil")).toBeInTheDocument();
    const recipients = screen.getByRole("list", { name: "Destinatarios" });
    expect(within(recipients).getByText(/Ferretería El Constructor · 0990000000001/)).toBeInTheDocument();
    expect(within(recipients).getByText(/001-002-000004567/)).toBeInTheDocument();
    expect(within(recipients).getByText("Tubo PVC 110 mm x 3 m")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Copiar clave de acceso" }));
    await expect(navigator.clipboard.readText()).resolves.toBe(CLAVE);
  });

  it("una guía peruana genérica se nombra por su código SUNAT", () => {
    render(<LogisticsDocumentsTable documents={[document({
      documentType: "REMISSION_GUIDE",
      fiscal: {
        fiscalCountry: "PE", fiscalDocumentCode: "31", authorizationNumber: null, transportStartDate: null,
        transportEndDate: null, carrierTaxId: null, carrierName: null, vehiclePlate: null, originAddress: null,
        transferReason: null,
      },
    })]} />);

    expect(screen.getByText("GRE transportista (SUNAT 31)")).toBeInTheDocument();
  });
});
