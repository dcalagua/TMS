import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { OrderDetailView, OrderLineView } from "../../shared/api/ordersApi";
import { OrderGeometrySection } from "./OrderGeometrySection";

/**
 * «Ocupa» en el detalle del pedido (ADR-022 §10), sobre el caso PLAPASA: una línea de vasos
 * anidables medida, una de gavetas con perfil incompleto y una sin bloque físico. Cada una tiene
 * que decir lo suyo: la medida sus pilas y su volumen efectivo, la incompleta lo que falta, y la que
 * no tiene perfil que cuenta su volumen declarado.
 */
const BASE_LINE: OrderLineView = {
  id: "l1", lineNumber: 1, materialCode: "PLP-571", materialDescription: "Vaso 16 oz",
  quantity: 1000, uom: "EA", unitWeightKg: 0.01, unitVolumeM3: 0.00193, lineWeightKg: 10, lineVolumeM3: 1.93,
  palletQuantity: null,
};

const LINES: OrderLineView[] = [
  {
    ...BASE_LINE, stackMode: "NESTABLE", physicalSource: "PROFILE", geometryStatus: "MEASURED",
    stacks: 10, stackHeightM: 1.363, effectiveVolumeM3: 0.1503, floorAreaM2: 0.1103,
  },
  {
    ...BASE_LINE, id: "l2", lineNumber: 2, materialCode: "PLP-1601", lineVolumeM3: 0.5,
    stackMode: "STACKABLE", physicalSource: "ORDER_LINE", geometryStatus: "UNMEASURED", geometryReason: "MISSING_HEIGHT",
  },
  { ...BASE_LINE, id: "l3", lineNumber: 3, materialCode: "TAPA-01", lineVolumeM3: 0.2, geometryStatus: null },
];

const ORDER = {
  id: "o1", orderNumber: "TO-00000042", totalVolumeM3: 2.63, lines: LINES,
  geometry: {
    planningVolumeM3: 0.8503, floorAreaM2: 0.1103, maxStackHeightM: 1.363, maxUnitHeightM: 0.175,
    stacks: 10, unmeasuredLines: 1, referenceHeightM: 2.4, allocatedFloorAreaM2: 0, pendingFloorAreaM2: 0.1103,
  },
} as unknown as OrderDetailView;

describe("OrderGeometrySection", () => {
  it("resume lo que ocupa el pedido y lo que ahorra frente al declarado", () => {
    render(<OrderGeometrySection order={ORDER} />);
    // Una vez en el resumen y otra en la línea medida (la única con pilas).
    expect(screen.getAllByText("10 pilas · 0,11 m² de piso · alto 1,36 m")).toHaveLength(2);
    expect(screen.getByText("Ahorra 1,78 m³ (68 %)")).toBeInTheDocument();
    expect(screen.getByText("0,85 m³ efectivos · 2,63 m³ declarados")).toBeInTheDocument();
    expect(screen.getByText("2,4 m")).toBeInTheDocument();
    expect(screen.getByText("1 línea no se pudo medir: cuenta con su volumen declarado.")).toBeInTheDocument();
  });

  it("dice por línea el modo, las pilas y el motivo cuando no se midió", () => {
    render(<OrderGeometrySection order={ORDER} />);
    expect(screen.getByText("Anidable")).toBeInTheDocument();
    expect(screen.getByText("Apilable")).toBeInTheDocument();
    expect(screen.getByText("10 pilas · 0,11 m² de piso · alto 1,36 m", { selector: "td p" })).toBeInTheDocument();
    expect(screen.getByText("0,15 m³ efectivos · 1,93 m³ declarados")).toBeInTheDocument();
    expect(screen.getByText("Falta el alto de la unidad")).toBeInTheDocument();
    expect(screen.getByText("Sin perfil físico: cuenta su volumen declarado")).toBeInTheDocument();
  });

  it("sin ninguna línea con geometría lo dice en una frase", () => {
    const plain = {
      ...ORDER, lines: [LINES[2]],
      geometry: { ...ORDER.geometry, maxStackHeightM: null, stacks: 0, floorAreaM2: 0, unmeasuredLines: 0 },
    } as OrderDetailView;
    render(<OrderGeometrySection order={plain} />);
    expect(screen.getByText(/se planifica por su volumen declarado/)).toBeInTheDocument();
  });
});
