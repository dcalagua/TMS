import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { BlockersPanel } from "./ControlTowerPanels";
import type { ControlTowerBlockerView } from "../../shared/api/controlTowerApi";

/**
 * El panel de bloqueos de la torre (JOB 12).
 *
 * Lo que se protege no es el maquetado. Es que el panel siga diciendo dos cosas que un tablero
 * operativo tiene que decir y que es fácil perder en un refactor:
 *
 * 1. **Cero se dice en voz alta.** Un panel vacío puede significar "no hay nada atascado" o "nadie
 *    miró", y un despachador no puede distinguirlos. La frase explícita sí.
 * 2. **El detalle viaja entero.** Es el texto que dice qué hacer - hasta cuándo dura el bloqueo,
 *    qué transportista aceptó - y recortarlo deja al lector con un motivo y sin acción.
 */

function panel(items: ControlTowerBlockerView[], total = items.length) {
  return render(
    <MemoryRouter>
      <BlockersPanel items={items} total={total} />
    </MemoryRouter>,
  );
}

const VEHICLE_BLOCK: ControlTowerBlockerView = {
  tripId: "11111111-1111-4111-8111-111111111111",
  tripNumber: 7,
  shipmentNumber: "SH-00000007",
  reason: "VEHICLE_UNAVAILABLE",
  detail: "Unavailable (MAINTENANCE) until 2026-09-07T19:00Z.",
};

const CARRIER_BLOCK: ControlTowerBlockerView = {
  tripId: "22222222-2222-4222-8222-222222222222",
  tripNumber: 8,
  shipmentNumber: "SH-00000008",
  reason: "AWAITING_CARRIER_VEHICLE",
  detail: "Accepted by a carrier that does not own the vehicle assigned to it.",
};

describe("el panel de bloqueos", () => {
  it("dice explícitamente que no hay nada atascado, en vez de quedarse vacío", () => {
    panel([]);

    // La afirmación que importa: un panel vacío no distingue "todo bien" de "nadie miró".
    expect(screen.getByText("Ningún envío bloqueado hoy.")).toBeInTheDocument();
  });

  it("nombra el motivo traducido y el envío al que pertenece", () => {
    panel([VEHICLE_BLOCK]);

    expect(screen.getByText("Vehículo no disponible")).toBeInTheDocument();
    expect(screen.getByText("SH-00000007")).toBeInTheDocument();
  });

  it("muestra el detalle completo, que es la parte que dice qué hacer", () => {
    panel([VEHICLE_BLOCK]);

    expect(screen.getByText(/MAINTENANCE/)).toBeInTheDocument();
  });

  it("distingue un vehículo de un transportista: son acciones de personas distintas", () => {
    panel([VEHICLE_BLOCK, CARRIER_BLOCK]);

    expect(screen.getByText("Vehículo no disponible")).toBeInTheDocument();
    expect(screen.getByText("Falta vehículo del transportista")).toBeInTheDocument();
  });

  /**
   * "Los primeros veinte de cuarenta" es una frase distinta de "hay veinte", y la segunda haría que
   * un despachador cerrara la pantalla creyendo haber visto el problema entero.
   */
  it("cuando la lista viene recortada, dice de cuántos es", () => {
    panel([VEHICLE_BLOCK, CARRIER_BLOCK], 40);

    expect(screen.getByText("2 de 40")).toBeInTheDocument();
  });

  it("cada bloqueo enlaza a su envío, que es a donde hay que ir a resolverlo", () => {
    panel([VEHICLE_BLOCK]);

    expect(screen.getByRole("link")).toHaveAttribute(
      "href", "/trips/11111111-1111-4111-8111-111111111111",
    );
  });
});
