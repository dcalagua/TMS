import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { TripView } from "../../shared/api/planningApi";
import type { DispatchDocumentView, TripWarehouseView } from "../../shared/api/warehouseApi";
import { enumLabel } from "../../lib/enums";
import { TripWarehouseCard } from "./TripWarehouseCard";
import {
  countBySeverity, orderMatchTone, planVsReal, severityTone, sortDocuments, sortMilestones, verificationTone,
} from "./warehouseDispatch";

/**
 * La tarjeta "Despacho de almacén" (ADR-013).
 *
 * Lo que se protege: el color de la verificación sale del estado del servidor y un valor nuevo no
 * rompe nada; una diferencia se marca por su código de discrepancia y no por una comparación del
 * navegador; y un envío sin documento lo dice con palabras.
 */

const TRIP = {
  id: "t-1",
  shipmentNumber: "SH-00000845",
  carrierName: "Transportes Andinos",
  vehicleLicensePlate: "ABC-123",
  driverName: "Pérez, Ana",
  originCode: "CD01",
  originName: "Centro Lima",
  actualDepartureAt: "2026-09-28T12:40:00Z",
} as TripView;

function doc(overrides: Partial<DispatchDocumentView> = {}): DispatchDocumentView {
  return {
    id: "d-1",
    sourceSystem: "EWM_EBIM",
    dispatchReference: "SLS-000001",
    revision: 1,
    current: true,
    outcome: "APPLIED",
    verificationStatus: "MISMATCH",
    loadReference: "CRG-000001",
    warehouseCode: "CD01",
    actualDispatchAt: "2026-09-28T12:35:00Z",
    carrierCode: "TA",
    carrierName: "Transportes Andinos",
    vehicleLicensePlate: "XYZ 999",
    driverName: "Ana Pérez",
    driverDocumentNumber: "12345678",
    sealNumber: "PR-77",
    transportDocumentNumber: "GRT-1",
    totalHandlingUnits: 12,
    totalWeightKg: 480,
    totalVolumeM3: 3.2,
    receivedAt: "2026-09-28T12:36:00Z",
    discrepancies: [
      { code: "VEHICLE_MISMATCH", severity: "ERROR", orderReference: null, lineNumber: null, planned: null, dispatched: null, uom: null, detail: "Placa distinta" },
      { code: "QUANTITY_VARIANCE", severity: "ERROR", orderReference: "PED-1011", lineNumber: 1, planned: 100, dispatched: 97, uom: "UN", detail: null },
      { code: "DRIVER_MISMATCH", severity: "WARNING", orderReference: null, lineNumber: null, planned: null, dispatched: null, uom: null, detail: null },
    ],
    orders: [
      { orderId: "o-1", externalSource: "SAPB1_PE", externalReference: "PED-1011", warehouseOrderNumber: "OUT-9", status: "SHIPPED", handlingUnits: 12, weightKg: 480, volumeM3: 3.2, matchResult: "VARIANCE" },
    ],
    ...overrides,
  };
}

function warehouse(overrides: Partial<TripWarehouseView> = {}): TripWarehouseView {
  return {
    tripId: "t-1",
    shipmentNumber: "SH-00000845",
    dispatchConfirmationMode: "EXTERNAL_REQUIRED",
    dispatchSource: "INTEGRATION",
    actualDepartureAt: "2026-09-28T12:35:00Z",
    verificationStatus: "MISMATCH",
    documents: [doc()],
    milestones: [
      { type: "LOAD_READY", eventId: "e-2", loadReference: "CRG-000001", warehouseCode: "CD01", occurredAt: "2026-09-28T12:00:00Z", receivedAt: "2026-09-28T12:00:05Z" },
      { type: "LOADING_STARTED", eventId: "e-1", loadReference: "CRG-000001", warehouseCode: "CD01", occurredAt: "2026-09-28T11:00:00Z", receivedAt: "2026-09-28T11:00:05Z" },
    ],
    ...overrides,
  };
}

describe("colores de la verificación", () => {
  it("pinta cada estado conocido y deja en gris uno que no conoce", () => {
    expect(verificationTone("MATCHED")).toBe("done");
    expect(verificationTone("MISMATCH")).toBe("overdue");
    expect(verificationTone("OVERRIDDEN")).toBe("inProgress");
    expect(verificationTone("UNVERIFIED")).toBe("neutral");
    expect(verificationTone("SOMETHING_NEW")).toBe("neutral");
    expect(verificationTone(null)).toBe("neutral");
  });

  it("colorea severidades y resultados por pedido con la misma regla", () => {
    expect(severityTone("ERROR")).toBe("overdue");
    expect(severityTone("WARNING")).toBe("inProgress");
    expect(severityTone("INFO")).toBe("open");
    expect(severityTone("CRITICAL")).toBe("neutral");
    expect(orderMatchTone("MATCHED")).toBe("done");
    expect(orderMatchTone("EXTRA")).toBe("overdue");
  });
});

describe("derivaciones", () => {
  it("pone primero la revisión vigente y luego lo más reciente", () => {
    const sorted = sortDocuments([
      doc({ id: "old", current: false, receivedAt: "2026-09-28T13:00:00Z" }),
      doc({ id: "cur", current: true, receivedAt: "2026-09-28T12:00:00Z" }),
      doc({ id: "older", current: false, receivedAt: "2026-09-28T10:00:00Z" }),
    ]);
    expect(sorted.map((entry) => entry.id)).toEqual(["cur", "old", "older"]);
  });

  it("ordena los hitos por cuándo ocurrieron", () => {
    expect(sortMilestones(warehouse().milestones).map((m) => m.type)).toEqual(["LOADING_STARTED", "LOAD_READY"]);
  });

  it("cuenta las diferencias por severidad", () => {
    expect(countBySeverity(doc())).toEqual({ ERROR: 2, WARNING: 1, INFO: 0 });
  });

  it("marca la diferencia por el código del servidor, no comparando textos", () => {
    const rows = planVsReal(TRIP, doc(), (value) => value);
    const byKey = Object.fromEntries(rows.map((row) => [row.key, row]));
    // Placa y conductor tienen su código; el transportista coincide aunque el texto difiera en formato.
    expect(byKey.plate.differs).toBe(true);
    expect(byKey.driver.differs).toBe(true);
    expect(byKey.carrier.differs).toBe(false);
    expect(byKey.warehouse.differs).toBe(false);
    expect(byKey.time.differs).toBe(false);
    expect(byKey.plate.planned).toBe("ABC-123");
    expect(byKey.plate.dispatched).toBe("XYZ 999");
    expect(byKey.carrier.dispatched).toBe("TA · Transportes Andinos");
  });
});

describe("la tarjeta de despacho de almacén", () => {
  it("dice que no hay confirmación cuando el almacén no ha mandado nada", () => {
    render(
      <TripWarehouseCard
        companyId="c-1" trip={TRIP} loading={false} failed={false} orderNumbers={new Map()}
        warehouse={warehouse({ documents: [], milestones: [], verificationStatus: "UNVERIFIED", dispatchSource: null })}
      />,
    );

    expect(screen.getByText("Sin confirmación de almacén")).toBeInTheDocument();
    expect(screen.getByText("Sin verificar")).toBeInTheDocument();
  });

  it("enseña las diferencias en español, con el pedido de TMS y la acción de ver el original", () => {
    render(
      <TripWarehouseCard
        companyId="c-1" trip={TRIP} loading={false} failed={false}
        orderNumbers={new Map([["o-1", "TO-00000011"]])}
        warehouse={warehouse()}
      />,
    );

    // Tarjeta y documento dicen los dos "Con diferencias".
    expect(screen.getAllByText("Con diferencias").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(enumLabel("dispatchDiscrepancyCode", "QUANTITY_VARIANCE"))).toBeInTheDocument();
    expect(screen.getByText("PED-1011")).toBeInTheDocument();
    expect(screen.getByText("TO-00000011")).toBeInTheDocument();
    expect(screen.getByText("Carga iniciada")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Ver documento original/ })).toBeInTheDocument();
  });

  it("no rompe ante un código de discrepancia que esta versión no conoce", () => {
    render(
      <TripWarehouseCard
        companyId="c-1" trip={TRIP} loading={false} failed={false} orderNumbers={new Map()}
        warehouse={warehouse({
          documents: [doc({
            discrepancies: [{ code: "SEAL_MISMATCH", severity: "ERROR", orderReference: null, lineNumber: null, planned: null, dispatched: null, uom: null, detail: "Precinto distinto" }],
          })],
        })}
      />,
    );

    expect(screen.getByText("SEAL_MISMATCH")).toBeInTheDocument();
    expect(screen.getByText("Precinto distinto")).toBeInTheDocument();
  });
});
