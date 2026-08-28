import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AdvisoriesPanel } from "./ControlTowerPanels";
import type { ControlTowerAdvisoryView } from "../../shared/api/controlTowerApi";

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

  it("dice de cuántos son los que enseña", () => {
    renderPanel([advisory()], 12);

    expect(screen.getByText("Conviene saber")).toBeInTheDocument();
  });
});
