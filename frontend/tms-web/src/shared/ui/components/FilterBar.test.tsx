import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DateRangeFilter, FilterBar } from "./FilterBar";

describe("el filtro de periodo", () => {
  it("dice qué periodo rige aunque sea el de por defecto", () => {
    render(
      <DateRangeFilter label="Periodo" required maxDays={30}
        from="2026-10-14" to="2026-11-12" defaultFrom="2026-10-14" defaultTo="2026-11-12" onChange={vi.fn()} />,
    );
    expect(screen.getByRole("button", { name: /^Periodo: .+2026/ })).toBeInTheDocument();
  });

  it("obligatorio, no se puede quitar ni con un periodo elegido a mano", () => {
    render(
      <DateRangeFilter label="Periodo" required
        from="2026-11-01" to="2026-11-05" defaultFrom="2026-10-14" defaultTo="2026-11-12" onChange={vi.fn()} />,
    );
    expect(screen.queryByRole("button", { name: "Quitar filtro Periodo" })).not.toBeInTheDocument();
  });

  it("opcional, se quita con la ✕ y vuelve al de por defecto", () => {
    const onChange = vi.fn();
    render(
      <DateRangeFilter label="Periodo"
        from="2026-11-01" to="2026-11-05" defaultFrom="" defaultTo="" onChange={onChange} />,
    );
    screen.getByRole("button", { name: "Quitar filtro Periodo" }).click();
    expect(onChange).toHaveBeenCalledWith("", "");
  });

  it("en la barra, un periodo obligatorio tampoco lleva ✕", () => {
    render(
      <FilterBar
        value={{ from: "2026-11-01", to: "2026-11-05" }}
        defaults={{ from: "2026-11-06", to: "2026-11-12" }}
        onChange={vi.fn()}
        fields={[{ type: "dateRange", from: "from", to: "to", label: "Fecha", required: true, maxDays: 30 }]}
      />,
    );
    expect(screen.getByRole("button", { name: /^Fecha/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Quitar filtro Fecha" })).not.toBeInTheDocument();
    // «Limpiar» no vacía el periodo: lo devuelve a la semana de por defecto.
    expect(screen.getByRole("button", { name: "Limpiar" })).toBeInTheDocument();
  });
});
