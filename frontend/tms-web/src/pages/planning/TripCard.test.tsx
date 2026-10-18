import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { CapacityDimension, TripView } from "../../shared/api/planningApi";
import { TripCard } from "./TripCard";

/**
 * La tarjeta del tablero con las cinco dimensiones (V62): piso y alto aparecen cuando dicen algo
 * —el vehículo los declara o hay algo medido— y la dimensión que llena el camión se señala.
 */
const d = (used: number, limit: number | null, percentUsed: number | null, exceeded = false): CapacityDimension => ({
  used, limit, remaining: limit === null ? null : limit - used, percentUsed, exceeded, unlimited: limit === null,
});

function trip(capacity: Partial<TripView["capacity"]>): TripView {
  return {
    id: "t1", tripNumber: 3, shipmentNumber: "SH-00000003", status: "DRAFT",
    vehicleCode: "VAN-01", vehicleLicensePlate: "ABC-123", vehicleTypeCode: "VAN", carrierName: null,
    driverName: "Pérez, Ana", plannedDepartureAt: null, orderCount: 2, stopCount: 2, routeCode: null,
    capacity: {
      tripId: "t1", source: "LIVE", orderCount: 2,
      weight: d(400, 1000, 40), volume: d(0.3, 10, 3), pallets: d(0, 4, 0), withinCapacity: true,
      ...capacity,
    },
  } as unknown as TripView;
}

describe("TripCard", () => {
  it("muestra piso y alto cuando el vehículo los declara, y señala lo que llena el camión", () => {
    render(<TripCard onOpen={vi.fn()} trip={trip({
      floor: d(5.5, 6.12, 89.9), height: d(1.36, 2.5, 54.4), bindingDimension: "FLOOR",
    })} />);
    expect(screen.getByText("Piso")).toBeInTheDocument();
    expect(screen.getByText("90 %")).toBeInTheDocument();
    expect(screen.getByText("1,36 m / 2,5 m")).toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "Piso · lo que llena el camión" })).toBeInTheDocument();
  });

  it("esconde piso y alto si no hay límite ni nada medido", () => {
    render(<TripCard onOpen={vi.fn()} trip={trip({ floor: d(0, null, null), height: d(0, null, null) })} />);
    expect(screen.queryByText("Piso")).not.toBeInTheDocument();
    expect(screen.queryByText("Alto")).not.toBeInTheDocument();
  });

  it("tolera un backend sin piso ni alto", () => {
    render(<TripCard onOpen={vi.fn()} trip={trip({})} />);
    expect(screen.getByText("Peso")).toBeInTheDocument();
  });
});
