import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { LogisticsDocumentView } from "../api/logisticsDocumentsApi";
import { LogisticsDocumentsTable } from "./LogisticsDocumentsTable";

/**
 * Los documentos del ERP (ADR-015): el número tal cual se emitió, el tipo en español y el
 * importe ausente como hueco, nunca como cero.
 */

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

describe("la tabla de documentos logísticos", () => {
  it("enseña el tipo en español y el número tal cual", () => {
    render(<LogisticsDocumentsTable documents={[document()]} />);

    expect(screen.getByText("Guía de remisión remitente")).toBeInTheDocument();
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
});
