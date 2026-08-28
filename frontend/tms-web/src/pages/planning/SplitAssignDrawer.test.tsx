import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SplitAssignDrawer } from "./SplitAssignDrawer";
import type { EligibleOrderView, TripView } from "../../shared/api/planningApi";

/**
 * El panel de reparto (migración V37).
 *
 * Lo que se protege es que el formulario arranque sobre lo *pendiente* y no sobre el total, y
 * que no deje enviar un reparto que el backend va a rechazar. La regla de verdad es del servidor
 * — `ck_transport_order_not_over_allocated` — y esto no la sustituye; evita el viaje de ida y
 * vuelta para el error más fácil de cometer, que es teclear el total del pedido cuando ya hay
 * parte cargada en otro camión.
 */

const ORDER: EligibleOrderView = {
  id: "order-1",
  orderNumber: "TO-000123",
  originId: "origin-1",
  destinationId: "dest-1",
  destinationCode: "DEST-A1",
  destinationName: "Tienda Centro",
  customerName: "Cliente Uno",
  customerReference: null,
  serviceDate: "2026-09-01",
  priority: "NORMAL",
  requestedWindowStart: null,
  requestedWindowEnd: null,
  totalWeightKg: 1000,
  totalVolumeM3: 10,
  totalPallets: 100,
  // Ya hay 70 pallets en otro camión: quedan 30.
  pendingWeightKg: 300,
  pendingVolumeM3: 3,
  pendingPallets: 30,
  partiallyAllocated: true,
};

const TRIPS = [
  { id: "trip-1", tripNumber: "TR-0001", vehicleCode: "PLT-00001" },
  { id: "trip-2", tripNumber: "TR-0002", vehicleCode: null },
] as unknown as TripView[];

function renderDrawer(onSubmit = vi.fn()) {
  render(
    <SplitAssignDrawer
      open order={ORDER} trips={TRIPS} submitting={false}
      onClose={vi.fn()} onSubmit={onSubmit}
    />,
  );
  return onSubmit;
}

describe("el panel de reparto", () => {
  it("arranca con lo pendiente, no con el total del pedido", () => {
    renderDrawer();

    // 30 pallets pendientes, no los 100 del pedido: prellenar el total sería ofrecer por defecto
    // la única cifra que el servidor rechaza.
    expect(screen.getByLabelText("Pallets")).toHaveValue(30);
    expect(screen.getByLabelText("Peso (kg)")).toHaveValue(300);
    expect(screen.getByLabelText("Volumen (m3)")).toHaveValue(3);
  });

  it("envía la parte tecleada junto al viaje elegido", async () => {
    const onSubmit = renderDrawer();
    const user = userEvent.setup();

    await user.clear(screen.getByLabelText("Pallets"));
    await user.type(screen.getByLabelText("Pallets"), "20");
    await user.click(screen.getByRole("button", { name: "Asignar esta parte" }));

    expect(onSubmit).toHaveBeenCalledWith("trip-1", { weightKg: 300, volumeM3: 3, pallets: 20 });
  });

  it("no deja pedir más de lo que queda pendiente", async () => {
    const onSubmit = renderDrawer();
    const user = userEvent.setup();

    await user.clear(screen.getByLabelText("Pallets"));
    await user.type(screen.getByLabelText("Pallets"), "40");

    expect(screen.getByRole("button", { name: "Asignar esta parte" })).toBeDisabled();
    expect(screen.getByText("No se puede asignar más de lo que queda pendiente.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("no deja enviar un reparto vacío", async () => {
    const onSubmit = renderDrawer();
    const user = userEvent.setup();

    for (const label of ["Pallets", "Peso (kg)", "Volumen (m3)"]) {
      await user.clear(screen.getByLabelText(label));
      await user.type(screen.getByLabelText(label), "0");
    }

    expect(screen.getByRole("button", { name: "Asignar esta parte" })).toBeDisabled();
    expect(screen.getByText("Un reparto tiene que llevar algo: peso, volumen o pallets.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("dice cuánto queda pendiente en vez de dejarlo al cálculo mental", () => {
    renderDrawer();

    expect(screen.getByText("Pendiente de planificar")).toBeInTheDocument();
  });
});
