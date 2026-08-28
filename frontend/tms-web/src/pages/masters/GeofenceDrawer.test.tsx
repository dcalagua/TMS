import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GeofenceDrawer } from "./GeofenceDrawer";
import type { LocationView } from "../../shared/api/locationsApi";

/**
 * El cajón de geocerco (migración V43, ADR-011).
 *
 * Dos cosas que la pantalla tiene que decir y que se pierden fácil en un refactor de maquetado:
 *
 * 1. **Un geocerco informa y no mueve nada.** ADR-007 lo dice y esta pantalla es donde alguien lo
 *    configura - si no lo leyera aquí, se iría creyendo que activó detección automática de
 *    llegadas, que es justo lo que TMS no hace.
 * 2. **Sin coordenadas no hay círculo.** El backend lo rechaza; la pantalla lo dice antes y
 *    deshabilita el guardado, para no ofrecer una acción que sólo puede fallar.
 */

const BASE: LocationView = {
  id: "11111111-1111-4111-8111-111111111111",
  code: "ALM-LIMA",
  name: "Almacén Lima",
  type: "WAREHOUSE",
  roles: ["ORIGIN"],
  address: "Av. Argentina 1234",
  addressReference: null,
  district: "Callao",
  province: "Callao",
  department: "Callao",
  country: "PE",
  timeZone: "America/Lima",
  latitude: -12.0456,
  longitude: -77.0317,
  zoneId: null,
  zoneCode: null,
  zoneName: null,
  serviceTimeMinutes: 25,
  geofenceRadiusM: null,
  externalSystem: null,
  externalReference: null,
  active: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
} as LocationView;

function drawer(location: LocationView) {
  return render(
    <GeofenceDrawer companyId="c-1" location={location} onClose={() => {}} onSaved={() => {}} />,
  );
}

describe("el cajón de geocerco", () => {
  /** La frase que impide que alguien configure esto esperando detección automática de llegadas. */
  it("dice que un geocerco informa y no cambia el estado de ninguna parada", () => {
    drawer(BASE);

    expect(screen.getByText(/No cambia el estado de ninguna parada/)).toBeInTheDocument();
  });

  it("explica que dejarlo vacío quita el geocerco, porque null no es cero", () => {
    drawer(BASE);

    expect(screen.getByText(/Vacío quita el geocerco/)).toBeInTheDocument();
  });

  it("sin coordenadas avisa antes y no deja guardar", () => {
    drawer({ ...BASE, latitude: null, longitude: null });

    expect(screen.getByText(/un círculo alrededor de nada/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar" })).toBeDisabled();
  });

  it("con coordenadas deja guardar", () => {
    drawer(BASE);

    expect(screen.getByRole("button", { name: "Guardar" })).toBeEnabled();
  });

  it("parte del radio que el sitio ya tiene, para no borrarlo por abrir la pantalla", () => {
    drawer({ ...BASE, geofenceRadiusM: 250 });

    expect(screen.getByLabelText("Radio (metros)")).toHaveValue(250);
  });
});
