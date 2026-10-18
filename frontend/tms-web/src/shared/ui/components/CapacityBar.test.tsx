import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CapacityBar } from "./layout";

/**
 * Las dos dimensiones que V62 sumó a la barra de capacidad: piso (m²) y alto de pila (m). Se fija
 * que se lean en su unidad, que el exceso y la cercanía se digan con palabras y no solo con color,
 * y que "sin límite" con vehículo se explique como medida no declarada en su tipo.
 */
const dimension = (used: number, limit: number | null, percentUsed: number | null, exceeded = false) => ({
  used, limit, percentUsed, exceeded, unlimited: limit === null,
});

describe("CapacityBar · piso y alto", () => {
  it("dice el piso usado contra el útil, en m²", () => {
    render(<CapacityBar kind="floor" dimension={dimension(5.5, 12.24, 44.9)} />);
    expect(screen.getByText("Piso")).toBeInTheDocument();
    expect(screen.getByText("5,5 m² / 12,24 m² · 45%")).toBeInTheDocument();
  });

  it("marca en ámbar desde el 85 % y en rojo lo excedido", () => {
    const { rerender } = render(<CapacityBar kind="height" dimension={dimension(2.2, 2.5, 88)} />);
    expect(screen.getByText("Cerca del límite")).toBeInTheDocument();
    rerender(<CapacityBar kind="height" dimension={dimension(2.73, 2.5, 109.2, true)} />);
    expect(screen.getByText("Capacidad excedida")).toBeInTheDocument();
    expect(screen.getByText("2,73 m / 2,5 m · 109%")).toBeInTheDocument();
  });

  it("explica la falta de límite según haya o no vehículo", () => {
    const { rerender } = render(<CapacityBar kind="height" dimension={dimension(1.36, null, null)} vehicleAssigned />);
    expect(screen.getByText("1,36 m · El vehículo no declara alto")).toBeInTheDocument();
    rerender(<CapacityBar kind="floor" dimension={dimension(0.11, null, null)} />);
    expect(screen.getByText("0,11 m² · Sin límite")).toBeInTheDocument();
  });
});
