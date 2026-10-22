import { useEffect } from "react";
import { act, render, screen } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { isIsoDay, oneOf, useUrlFilters } from "./useUrlFilters";

/**
 * Ítem 11: los filtros de las pantallas de seguimiento viven en la URL. Lo que se prueba es el
 * contrato del gancho: lee lo que trae el enlace, escribe sólo lo que difiere del defecto, ignora
 * lo inválido y no toca las demás claves.
 */

type Filters = { day: string; status: string; q: string };
const DEFAULTS: Filters = { day: "2026-10-22", status: "IN_TRANSIT", q: "" };

/** El setter del gancho, capturado por la sonda para que la prueba lo llame. */
const probe: { set: (next: Filters) => void } = { set: () => {} };

function Probe() {
  const [filters, set] = useUrlFilters(DEFAULTS, { day: isIsoDay, status: oneOf(["", "IN_TRANSIT", "COMPLETED"]) });
  useEffect(() => { probe.set = set; }, [set]);
  const location = useLocation();
  return (
    <>
      <output data-testid="filters">{JSON.stringify(filters)}</output>
      <output data-testid="search">{location.search}</output>
    </>
  );
}

function renderAt(url: string) {
  render(
    <MemoryRouter initialEntries={[url]}>
      <Probe />
    </MemoryRouter>,
  );
}

const filters = () => JSON.parse(screen.getByTestId("filters").textContent ?? "{}") as Filters;
const search = () => screen.getByTestId("search").textContent ?? "";

describe("useUrlFilters", () => {
  it("sin parámetros rige el defecto y la URL queda limpia", () => {
    renderAt("/delivery-tracking");
    expect(filters()).toEqual(DEFAULTS);
    expect(search()).toBe("");
  });

  it("abre con lo que trae el enlace (una recarga conserva ?day=2026-10-20)", () => {
    renderAt("/delivery-tracking?day=2026-10-20&status=COMPLETED");
    expect(filters()).toEqual({ day: "2026-10-20", status: "COMPLETED", q: "" });
  });

  it("un valor que no pasa su validación se ignora y rige el defecto", () => {
    renderAt("/delivery-tracking?day=ayer&status=BOGUS&q=SH-1");
    expect(filters()).toEqual({ day: "2026-10-22", status: "IN_TRANSIT", q: "SH-1" });
  });

  it("escribe sólo lo que difiere del defecto, incluido un «todos» vacío, y conserva las demás claves", () => {
    renderAt("/delivery-tracking?tab=map");

    act(() => probe.set({ day: "2026-10-20", status: "", q: "" }));
    const params = new URLSearchParams(search());
    expect(params.get("day")).toBe("2026-10-20");
    expect(params.get("status")).toBe("");
    expect(params.has("q")).toBe(false);
    expect(params.get("tab")).toBe("map");
    expect(filters()).toEqual({ day: "2026-10-20", status: "", q: "" });

    act(() => probe.set(DEFAULTS));
    expect(search()).toBe("?tab=map");
  });
});
