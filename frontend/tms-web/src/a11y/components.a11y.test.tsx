import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { axe } from "./axe";
import { AdvisoriesPanel } from "../pages/control-tower/ControlTowerPanels";
import { OwnFleetCostBreakdown } from "../pages/costing/OwnFleetCostBreakdown";
import type { OwnFleetQuoteView } from "../shared/api/ownFleetCostingApi";

/**
 * Una barrida de axe sobre los componentes que este proyecto construyó (JOB 26).
 *
 * **Esto es una base, no accesibilidad.** axe automatiza cerca de un tercio de la WCAG. Que estas
 * pruebas pasen significa que no hay un botón sin nombre ni un campo sin etiqueta; no significa que
 * alguien con lector de pantalla pueda usar la pantalla. La deuda D9 sigue abierta y
 * `docs/frontend/ACCESSIBILITY.md` dice qué falta.
 */

function wrap(ui: React.ReactNode) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

const quote: OwnFleetQuoteView = {
  tripId: "t-1",
  nature: "OWN_FLEET_INTERNAL_COST",
  currency: "PEN",
  comparableTotal: 316.6,
  partialSubtotal: 316.6,
  complete: true,
  profileId: "p-1",
  profileScope: "VEHICLE",
  blockingReasons: [],
  unavailableReason: null,
  lines: [
    { component: "FIXED_TRIP", status: "APPLIED", rate: 100, quantity: null, unit: null,
      quantitySource: "PROFILE_FLAT", amount: 100, reason: null },
    { component: "FUEL_PER_KM", status: "APPLIED", rate: 0.65, quantity: 120, unit: "KM",
      quantitySource: "MEASURED_ROUTE", amount: 78, reason: null },
  ],
};

describe("barrida de accesibilidad automatizada", () => {
  it("el panel de avisos no tiene violaciones detectables", async () => {
    const { container } = wrap(
      <AdvisoriesPanel
        items={[{
          type: "SETTLEMENT_DISCREPANCY_OPEN", tripId: "t-1", shipmentNumber: "SHP-1",
          sourceId: "d-1", amount: 140, currency: "PEN", detail: "Diferencia de 140.00.",
        }]}
        total={1}
      />,
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it("el panel de avisos vacío tampoco", async () => {
    const { container } = wrap(<AdvisoriesPanel items={[]} total={0} />);

    expect(await axe(container)).toHaveNoViolations();
  });

  it("el desglose de costo de flota propia no tiene violaciones detectables", async () => {
    const { container } = wrap(<OwnFleetCostBreakdown quote={quote} />);

    expect(await axe(container)).toHaveNoViolations();
  });

  it("el desglose sin total tampoco — que es el caso que más texto añade", async () => {
    const { container } = wrap(
      <OwnFleetCostBreakdown
        quote={{
          ...quote, comparableTotal: null, complete: false,
          blockingReasons: ["DISTANCE_UNKNOWN"], partialSubtotal: 100,
          lines: [{
            component: "FUEL_PER_KM", status: "NOT_CALCULABLE", rate: 0.65, quantity: null,
            unit: "KM", quantitySource: null, amount: 0, reason: "DISTANCE_UNKNOWN",
          }],
        }}
      />,
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});

describe("los componentes compartidos, que salen en todas las pantallas", () => {
  it("DataTable con filas", async () => {
    const { DataTable } = await import("../shared/ui/components");
    const { container } = wrap(
      <DataTable
        columns={[
          { key: "code", header: "Código", render: (row: { code: string }) => row.code },
          { key: "name", header: "Nombre", render: (row: { name: string }) => row.name },
        ]}
        rows={[{ code: "A-1", name: "Uno" }, { code: "A-2", name: "Dos" }]}
        rowKey={(row: { code: string }) => row.code}
        caption="Tabla de prueba"
      />,
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it("DataTable vacía — el estado que más HTML condicional tiene", async () => {
    const { DataTable } = await import("../shared/ui/components");
    const { container } = wrap(
      <DataTable
        columns={[{ key: "code", header: "Código", render: () => null }]}
        rows={[]}
        rowKey={() => "x"}
        caption="Tabla vacía"
        emptyMessage="No hay nada."
      />,
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it("PageHeader", async () => {
    const { PageHeader } = await import("../shared/ui/components");
    const { container } = wrap(<PageHeader title="Título" subtitle="Subtítulo" />);

    expect(await axe(container)).toHaveNoViolations();
  });
});
