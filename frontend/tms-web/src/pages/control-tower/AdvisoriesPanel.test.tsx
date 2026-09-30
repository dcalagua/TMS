import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AdvisoriesPanel } from "./ControlTowerPanels";
import { ADVISORY_TYPES, type ControlTowerAdvisoryView } from "../../shared/api/controlTowerApi";
import { advisoryLabel, advisoryLink, isKnownAdvisory } from "./advisories";

/**
 * El panel de avisos, y la línea que no puede cruzar (JOB 23).
 *
 * La regla que estas pruebas defienden: **un aviso no detiene nada**, y la pantalla tiene que
 * decirlo con palabras y con color. El panel de bloqueadores tiene su propia prueba y su propio
 * contador; ninguna fila de aquí aparece allí.
 */

function advisory(overrides: Partial<ControlTowerAdvisoryView> = {}): ControlTowerAdvisoryView {
  return {
    type: "SETTLEMENT_DISCREPANCY_OPEN",
    tripId: "t-1",
    shipmentNumber: "SHP-0001",
    sourceId: "d-1",
    amount: 140,
    currency: "PEN",
    detail: "El transportista facturó 140.00 más de lo esperado.",
    ...overrides,
  };
}

function renderPanel(items: ControlTowerAdvisoryView[], total = items.length) {
  return render(
    <MemoryRouter>
      <AdvisoriesPanel items={items} total={total} />
    </MemoryRouter>,
  );
}

describe("el panel de avisos", () => {
  it("dice en voz alta que no hay nada que mirar", () => {
    renderPanel([]);

    // No se deduce de un panel vacío, igual que "ningún envío bloqueado hoy".
    expect(screen.getByText("Nada pendiente de mirar hoy.")).toBeInTheDocument();
  });

  it("muestra la diferencia cuando la hay", () => {
    renderPanel([advisory()]);

    expect(screen.getByText("Diferencia en factura sin resolver")).toBeInTheDocument();
    expect(screen.getByText("PEN 140.00")).toBeInTheDocument();
  });

  it("no pinta importe cuando los dos lados no se pudieron comparar", () => {
    renderPanel([advisory({ amount: null, currency: null, detail: "Nunca costeamos este envío." })]);

    // Un "0.00" aquí diría que la factura coincide, que es justo lo contrario de lo que significa
    // un null (V46). Se omite la cifra entera.
    expect(screen.queryByText(/0\.00/)).not.toBeInTheDocument();
    expect(screen.getByText("Nunca costeamos este envío.")).toBeInTheDocument();
  });

  it("enlaza la discrepancia a Liquidaciones y no ofrece resolverla aquí", () => {
    renderPanel([advisory()]);

    const link = screen.getByRole("link");
    expect(link).toHaveAttribute("href", "/settlement?discrepancy=d-1");
    // La torre no es dueña de este estado: no hay ningún botón de acción en la fila.
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("enlaza un aviso de ETA al envío, que es donde se actúa", () => {
    renderPanel([advisory({
      type: "STOP_ETA_MISSES_WINDOW", sourceId: "s-9", amount: null, currency: null,
      detail: "La llegada estimada se sale de su ventana.",
    })]);

    expect(screen.getByRole("link")).toHaveAttribute("href", "/trips/t-1");
    expect(screen.getByText("La llegada estimada se sale de la ventana")).toBeInTheDocument();
  });

  it("etiqueta en español los avisos del despacho de almacén y enlaza al envío", () => {
    renderPanel([advisory({
      type: "DISPATCH_MISMATCH", sourceId: "doc-1", amount: null, currency: null,
      detail: "SLS-000001 no coincide con el plan.",
    })]);

    expect(screen.getByText("El despacho del almacén no coincide con el plan")).toBeInTheDocument();
    expect(screen.getByRole("link")).toHaveAttribute("href", "/trips/t-1");
  });

  it("un aviso sin sourceId sigue enlazando a su envío", () => {
    renderPanel([advisory({
      type: "AWAITING_WAREHOUSE_DISPATCH", sourceId: null, amount: null, currency: null,
      detail: "Pasó la salida planificada y el almacén no ha confirmado.",
    })]);

    expect(screen.getByText("Esperando el despacho del almacén")).toBeInTheDocument();
    expect(screen.getByRole("link")).toHaveAttribute("href", "/trips/t-1");
  });

  it("un despacho sin envío asociado se lee pero no enlaza a ningún envío", () => {
    renderPanel([advisory({
      type: "EXTERNAL_DISPATCH_UNMATCHED", tripId: null, shipmentNumber: "SH-99999999", sourceId: "doc-9",
      amount: null, currency: null, detail: "El almacén despachó un envío que no existe.",
    })]);

    expect(screen.getByText("Despacho del almacén sin envío asociado")).toBeInTheDocument();
    expect(screen.getByText("SH-99999999")).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("pinta un tipo que esta versión no conoce con una etiqueta genérica y su detalle", () => {
    renderPanel([advisory({
      type: "SOMETHING_NEW", sourceId: "x-1", amount: null, currency: null, detail: "Algo nuevo que mirar.",
    })]);

    expect(screen.getByText("Aviso de la operación")).toBeInTheDocument();
    expect(screen.getByText("SOMETHING_NEW")).toBeInTheDocument();
    expect(screen.getByText("Algo nuevo que mirar.")).toBeInTheDocument();
  });

  it("dice de cuántos son los que enseña", () => {
    renderPanel([advisory()], 12);

    expect(screen.getByText("Conviene saber")).toBeInTheDocument();
  });
});

describe("las reglas de los avisos", () => {
  it("cada tipo conocido tiene etiqueta propia", () => {
    for (const type of ADVISORY_TYPES) {
      expect(isKnownAdvisory(type)).toBe(true);
      expect(advisoryLabel(type)).not.toBe("Aviso de la operación");
    }
  });

  it("decide el destino sin inventar un enlace", () => {
    expect(advisoryLink({ type: "SETTLEMENT_DISCREPANCY_OPEN", tripId: "t-1", sourceId: "d-1" })).toBe("/settlement?discrepancy=d-1");
    expect(advisoryLink({ type: "ORDER_HOLD_ON_COMMITTED_TRIP", tripId: "t-2", sourceId: "o-1" })).toBe("/trips/t-2");
    expect(advisoryLink({ type: "EXTERNAL_DISPATCH_UNMATCHED", tripId: null, sourceId: "doc-1" })).toBeNull();
  });
});
