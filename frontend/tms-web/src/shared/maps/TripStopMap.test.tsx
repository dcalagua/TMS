import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { TripStopMap, type TripStopMapStop } from "./TripStopMap";

/**
 * El mapa de paradas de un viaje, ya sobre Leaflet.
 *
 * jsdom no pinta teselas, asi que lo que se protege aqui no es el dibujo sino las cuatro
 * promesas que la pestana Mapa le hace a un despachador y que un refactor rompe en silencio:
 *
 * 1. **El mapa aparece sin clave de API.** Es el cambio entero: antes, sin
 *    `VITE_GOOGLE_MAPS_API_KEY`, esta pestana mostraba "El mapa no esta disponible" y nada mas.
 * 2. **Una parada sin coordenadas no se dibuja, pero SE CUENTA.** Si desapareciera en silencio,
 *    el mapa estaria afirmando que el viaje tiene menos paradas de las que tiene.
 * 3. **El singular y el plural de ese aviso son textos distintos**, porque pasan por `t()` y una
 *    traduccion los separa.
 * 4. **El mapa no exige `onSelectStop`.** Es opcional en el contrato, y la pantalla de detalle
 *    lo omite.
 */

const STOPS: TripStopMapStop[] = [
  { id: "s1", sequence: 1, latitude: -12.0519, longitude: -77.0284, label: "Tienda Cercado" },
  { id: "s2", sequence: 2, latitude: -12.0928, longitude: -77.0334, label: "Tienda San Isidro" },
];

const ORIGIN = { latitude: -12.0432, longitude: -77.1063, label: "CD Callao" };

describe("TripStopMap", () => {
  it("dibuja el mapa sin ninguna clave de API configurada", () => {
    render(<TripStopMap origin={ORIGIN} stops={STOPS} />);

    expect(screen.getByRole("application", { name: "Mapa de paradas" })).toBeInTheDocument();
    // El aviso de degradacion es un `role="status"`: si aparece, el mapa no se monto.
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("no avisa de nada cuando todas las paradas tienen coordenadas", () => {
    render(<TripStopMap origin={ORIGIN} stops={STOPS} />);

    expect(screen.queryByText(/no aparece en el mapa|no aparecen en el mapa/)).not.toBeInTheDocument();
  });

  it("cuenta en singular la parada que no se puede mapear", () => {
    const stops = [...STOPS, { id: "s3", sequence: 3, latitude: null, longitude: null, label: "Sin coordenadas" }];
    render(<TripStopMap origin={ORIGIN} stops={stops} />);

    expect(screen.getByText("1 destino no tiene coordenadas y no aparece en el mapa.")).toBeInTheDocument();
  });

  it("cuenta en plural cuando son varias", () => {
    const stops = [
      ...STOPS,
      { id: "s3", sequence: 3, latitude: null, longitude: null, label: "Sin coordenadas" },
      { id: "s4", sequence: 4, latitude: null, longitude: null, label: "Tampoco" },
    ];
    render(<TripStopMap origin={ORIGIN} stops={stops} />);

    expect(screen.getByText("2 destinos no tienen coordenadas y no aparecen en el mapa.")).toBeInTheDocument();
  });

  it("se monta sin origen, sin paradas y sin vehiculo", () => {
    // Un viaje recien creado no tiene nada que dibujar, y eso no es un error: el mapa queda
    // centrado en el pais en vez de reventar.
    render(<TripStopMap origin={null} stops={[]} />);

    expect(screen.getByRole("application", { name: "Mapa de paradas" })).toBeInTheDocument();
  });

  it("acepta un vehiculo y una seleccion sin pedir onSelectStop", () => {
    const onSelectStop = vi.fn();
    const { rerender } = render(
      <TripStopMap origin={ORIGIN} stops={STOPS} selectedStopId="s2"
        vehicle={{ latitude: -12.07, longitude: -77.05, label: "ABC-123" }} />,
    );
    expect(screen.getByRole("application", { name: "Mapa de paradas" })).toBeInTheDocument();

    // Cambiar la seleccion redibuja los marcadores; el mapa tiene que sobrevivir al redibujado.
    rerender(
      <TripStopMap origin={ORIGIN} stops={STOPS} selectedStopId="s1" onSelectStop={onSelectStop}
        vehicle={{ latitude: -12.07, longitude: -77.05, label: "ABC-123" }} />,
    );
    expect(screen.getByRole("application", { name: "Mapa de paradas" })).toBeInTheDocument();
    expect(onSelectStop).not.toHaveBeenCalled();
  });
});
