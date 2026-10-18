import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { OccupancyCell, StackModeChip } from "./Occupancy";

/**
 * La celda «Ocupa» de las listas (pedidos y tablero). Lo que se protege: el pedido medido dice sus
 * pilas, su piso, su alto y el volumen efectivo frente al declarado; el que no se pudo medir lo dice
 * en ámbar; y el que no trae geometría no se lee como "no ocupa nada".
 */
describe("OccupancyCell", () => {
  it("muestra pilas, piso, alto y el volumen efectivo frente al declarado", () => {
    render(
      <OccupancyCell
        geometry={{ planningVolumeM3: 0.1503, floorAreaM2: 0.1103, maxStackHeightM: 1.363, stacks: 10, unmeasuredLines: 0 }}
        declaredVolumeM3={1.93}
      />,
    );
    expect(screen.getByText("10 pilas · 0,11 m² de piso · alto 1,36 m")).toBeInTheDocument();
    expect(screen.getByText("0,15 m³ efectivos · 1,93 m³ declarados")).toBeInTheDocument();
  });

  it("cuenta las líneas sin medir cuando la geometría es parcial", () => {
    render(
      <OccupancyCell
        geometry={{ planningVolumeM3: 1.2, floorAreaM2: 0.5, maxStackHeightM: 1.8, stacks: 4, unmeasuredLines: 2 }}
        declaredVolumeM3={2}
      />,
    );
    expect(screen.getByText(/2 líneas sin medir/)).toBeInTheDocument();
  });

  it("avisa cuando nada se pudo medir", () => {
    render(
      <OccupancyCell geometry={{ planningVolumeM3: 2, floorAreaM2: 0, maxStackHeightM: null, unmeasuredLines: 1 }} declaredVolumeM3={2} />,
    );
    expect(screen.getByText("1 línea sin medir")).toBeInTheDocument();
    expect(screen.getByText("Por volumen declarado")).toBeInTheDocument();
  });

  it("sin geometría dice que se planifica por lo declarado", () => {
    render(<OccupancyCell geometry={undefined} declaredVolumeM3={2} />);
    expect(screen.getByText("Por volumen declarado")).toBeInTheDocument();
  });

  it("en el tablero cabe en una línea", () => {
    render(
      <OccupancyCell dense geometry={{ planningVolumeM3: 0.1503, floorAreaM2: 0.1103, maxStackHeightM: 1.363, stacks: 10 }} declaredVolumeM3={1.93} />,
    );
    expect(screen.getByText("Ocupa: 10 pilas · 0,11 m² de piso · alto 1,36 m · 0,15 m³ efectivos · 1,93 m³ declarados"))
      .toBeInTheDocument();
  });
});

describe("StackModeChip", () => {
  it("nombra el modo con palabras, y lo no declarado como tal", () => {
    const { rerender } = render(<StackModeChip mode="NESTABLE" />);
    expect(screen.getByText("Anidable")).toBeInTheDocument();
    rerender(<StackModeChip mode="STACKABLE" />);
    expect(screen.getByText("Apilable")).toBeInTheDocument();
    rerender(<StackModeChip mode="NONE" />);
    expect(screen.getByText("Sin apilar")).toBeInTheDocument();
    rerender(<StackModeChip mode={null} />);
    expect(screen.getByText("No declarado")).toBeInTheDocument();
  });
});
