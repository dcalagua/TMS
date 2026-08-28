import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TripRouteCard } from "./TripRouteCard";
import type { TripRouteMetrics } from "../../shared/api/planningApi";

/**
 * La tarjeta de recorrido (migración V38).
 *
 * Lo que se protege no es el maquetado: es que la pantalla no presente una estimación como una
 * medición, y que un total corto por falta de coordenadas lo diga. Las dos son la clase de
 * regresión que un typechecker no ve y que en producción se convierte en una hora prometida a un
 * cliente sobre una línea recta.
 */

const BASE: TripRouteMetrics = {
  totalDistanceKm: 42.5,
  totalMinutes: 95,
  provider: "LOCAL_GEODESIC_V1",
  estimated: true,
  unmeasurableLegs: 0,
  complete: true,
  legs: [
    { fromStopSequence: null, fromLabel: "Almacén Lima", toStopSequence: 1, toLabel: "Tienda Centro", distanceKm: 20.0, travelMinutes: 45, estimated: true },
    { fromStopSequence: 1, fromLabel: "Tienda Centro", toStopSequence: 2, toLabel: "Tienda Sur", distanceKm: 22.5, travelMinutes: 50, estimated: true },
  ],
};

describe("la tarjeta de recorrido", () => {
  it("dice que la distancia es estimada cuando no hay proveedor de rutas", () => {
    render(<TripRouteCard routing={BASE} />);

    expect(screen.getByText("Estimado")).toBeInTheDocument();
  });

  it("nombra al proveedor y no marca estimado cuando la ruta fue medida", () => {
    render(<TripRouteCard routing={{ ...BASE, estimated: false, provider: "VENDOR_X" }} />);

    expect(screen.queryByText("Estimado")).not.toBeInTheDocument();
    expect(screen.getByText("VENDOR_X")).toBeInTheDocument();
  });

  it("muestra la distancia total y el tiempo en horas y minutos", () => {
    render(<TripRouteCard routing={BASE} />);

    expect(screen.getByText(/42[.,]5/)).toBeInTheDocument();
    // 95 minutos son 1 h 35 min: nadie lee un viaje en minutos sueltos.
    expect(screen.getByText("1 h 35 min")).toBeInTheDocument();
  });

  it("avisa cuando un tramo no se pudo medir, en vez de mostrar un total corto en silencio", () => {
    render(<TripRouteCard routing={{ ...BASE, complete: false, unmeasurableLegs: 2 }} />);

    expect(screen.getByText(/2 tramo\(s\) sin medir/)).toBeInTheDocument();
  });

  it("no avisa nada cuando el recorrido está completo", () => {
    render(<TripRouteCard routing={BASE} />);

    expect(screen.queryByText(/sin medir/)).not.toBeInTheDocument();
  });

  it("lista cada tramo con su origen y destino", () => {
    render(<TripRouteCard routing={BASE} />);

    expect(screen.getByText("Almacén Lima → Tienda Centro")).toBeInTheDocument();
    expect(screen.getByText("Tienda Centro → Tienda Sur")).toBeInTheDocument();
  });

  it("dice que el tiempo es solo de conducción", () => {
    render(<TripRouteCard routing={BASE} />);

    expect(screen.getByText(/Solo conducción/)).toBeInTheDocument();
  });

  it("un viaje sin tramos medibles lo dice en lugar de mostrar ceros", () => {
    render(<TripRouteCard routing={{ ...BASE, legs: [], totalDistanceKm: 0, totalMinutes: 0 }} />);

    expect(screen.getByText(/no tiene paradas con coordenadas/)).toBeInTheDocument();
  });
});
