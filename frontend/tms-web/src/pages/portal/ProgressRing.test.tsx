import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ProgressRing } from "./ProgressRing";

/** El anillo de la tarjeta de un viaje del portal: dice qué mide y cuánto lleva. */
describe("ProgressRing", () => {
  it("enseña hecho sobre total y se nombra por lo que mide", () => {
    render(<ProgressRing done={3} total={5} label="3 de 5 entregas registradas" />);

    expect(screen.getByRole("img", { name: "3 de 5 entregas registradas" })).toBeInTheDocument();
    expect(screen.getByText("3/5")).toBeInTheDocument();
  });

  it("un viaje sin nada que contar enseña 0/0 sin dividir por cero", () => {
    render(<ProgressRing done={0} total={0} label="0 de 0 entregas registradas" />);

    expect(screen.getByText("0/0")).toBeInTheDocument();
  });
});
