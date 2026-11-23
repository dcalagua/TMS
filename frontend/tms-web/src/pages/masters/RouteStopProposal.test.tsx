import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { RouteStopOptimizationView } from "../../shared/api/routesApi";
import { RouteStopProposal } from "./RouteStopProposal";

/**
 * «Optimizar paradas» (ADR-030): cuando el orden propuesto ya se aplicó, el banner dice cuánto se
 * ahorra, que las distancias son estimadas, y ofrece descartarlo. Nada más.
 */

function proposal(overrides: Partial<RouteStopOptimizationView> = {}): RouteStopOptimizationView {
  return {
    destinationIds: ["near", "far"],
    currentDistanceKm: 52.89,
    proposedDistanceKm: 48.43,
    improved: true,
    estimated: true,
    rationale: "Primero la parada 2, que está más cerca del origen.",
    model: "claude-opus-5-5",
    ...overrides,
  };
}

describe("el banner de la propuesta de orden del asistente de IA", () => {
  it("enseña el título, el ahorro y la nota de estimación, y descarta al pedirlo", async () => {
    const onDiscard = vi.fn();
    render(<RouteStopProposal proposal={proposal()} onDiscard={onDiscard} />);

    expect(screen.getByText("Orden propuesto")).toBeInTheDocument();
    expect(screen.getByText(/48,43 km en lugar de 52,89 km \(8 % menos\)/)).toBeInTheDocument();
    expect(screen.getByText(/Distancias estimadas a partir de las coordenadas/)).toBeInTheDocument();
    // El porqué del modelo y el orden nuevo ya no se enseñan: la lista misma es el orden nuevo.
    expect(screen.queryByText(/Primero la parada 2/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Aplicar orden" })).not.toBeInTheDocument();

    await userEvent.setup().click(screen.getByRole("button", { name: "Descartar" }));
    expect(onDiscard).toHaveBeenCalledOnce();
  });

  it("si no acorta nada lo dice, y sin estimaciones no lleva la nota", () => {
    render(<RouteStopProposal
      proposal={proposal({ improved: false, proposedDistanceKm: 52.89, estimated: false })}
      onDiscard={vi.fn()} />);

    expect(screen.getByText(/El orden actual ya es el más corto/)).toBeInTheDocument();
    expect(screen.queryByText(/Distancias estimadas/)).not.toBeInTheDocument();
  });
});
