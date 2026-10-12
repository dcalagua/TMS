import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { VehicleTrackMap } from "./VehicleTrackMap";

/**
 * El mapa de la ubicacion del vehiculo.
 *
 * Lo que se protege aqui es que el mapa aparezca en los tres casos en los que hay algo que
 * situar, porque los tres llegan de verdad desde el backend:
 *
 *  - una sola posicion y ningun rastro, que es lo normal en el primer reporte;
 *  - un rastro de varios puntos;
 *  - un rastro de un solo punto, donde no hay linea que dibujar pero si marcador.
 *
 * El limite entre "hay posicion" y "no hay" NO se decide aqui: `TripTrackingCard` distingue los
 * tres motivos de ausencia antes de llegar a este componente, y pasarle una posicion nula seria
 * un error de tipos, no un estado.
 */

const HERE = { latitude: -16.383, longitude: -71.556 };

describe("VehicleTrackMap", () => {
  it("dibuja el mapa con una sola posicion y sin rastro", () => {
    render(<VehicleTrackMap position={HERE} />);

    expect(screen.getByRole("application", { name: "Mapa de la ubicación del vehículo" })).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("dibuja el mapa con un rastro de varios puntos", () => {
    const track = [
      { latitude: -16.39, longitude: -71.535 },
      { latitude: -16.387, longitude: -71.545 },
      HERE,
    ];
    render(<VehicleTrackMap position={HERE} track={track} label="V8G-400" />);

    expect(screen.getByRole("application", { name: "Mapa de la ubicación del vehículo" })).toBeInTheDocument();
  });

  it("aguanta un rastro de un unico punto, donde no hay linea que dibujar", () => {
    render(<VehicleTrackMap position={HERE} track={[HERE]} />);

    expect(screen.getByRole("application", { name: "Mapa de la ubicación del vehículo" })).toBeInTheDocument();
  });

  it("se redibuja cuando llega una posicion nueva", () => {
    const { rerender } = render(<VehicleTrackMap position={HERE} track={[HERE]} />);
    const moved = { latitude: -16.37, longitude: -71.57 };

    rerender(<VehicleTrackMap position={moved} track={[HERE, moved]} />);

    expect(screen.getByRole("application", { name: "Mapa de la ubicación del vehículo" })).toBeInTheDocument();
  });
});
