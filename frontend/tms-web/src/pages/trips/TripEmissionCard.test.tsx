import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TripEmissionView } from "../../shared/api/emissionsApi";
import { TripEmissionCard } from "./TripEmissionCard";

/**
 * «Huella de carbono» del viaje (V73). Lo que se protege: una cifra con su procedencia (estimada si la
 * distancia lo es), el reparto por pedido, y «Sin datos» —nunca cero— cuando el tipo no tiene factor.
 */

const CALCULATED: TripEmissionView = {
  tripId: "trip-1", shipmentNumber: "VJ-00000003", status: "CALCULATED", kgCo2e: 112.5, distanceKm: 125,
  estimated: true, unmeasurableLegs: 0, provider: "LOCAL_GEODESIC", vehiclePlate: "GBA-1234",
  vehicleTypeId: "vt-1", vehicleTypeCode: "CAMION-10T", vehicleTypeName: "Camión 10 t", fuelType: "DIESEL",
  factorKgCo2ePerKm: 0.9, basis: "EFFECTIVE_VOLUME", weightKg: 2500, gramsPerTonneKm: 360,
  unallocatedOrderCount: 0, unallocatedReason: null,
  orders: [
    { assignmentId: "a-1", orderId: "o-1", orderNumber: "OT-0001", customerLabel: "Agroexportadora (demo)",
      destinationId: null, destinationLabel: null, basisQuantity: 10, share: 0.8, kgCo2e: 90 },
    { assignmentId: "a-2", orderId: "o-2", orderNumber: "OT-0002", customerLabel: "Ferretería (demo)",
      destinationId: null, destinationLabel: null, basisQuantity: 2.5, share: 0.2, kgCo2e: 22.5 },
  ],
};

let body: TripEmissionView = CALCULATED;

beforeEach(() => {
  body = CALCULATED;
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(body), {
    status: 200, headers: { "content-type": "application/json" },
  })));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderCard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <TripEmissionCard companyId="company-1" tripId="trip-1" version={3} />
    </QueryClientProvider>,
  );
}

describe("la tarjeta de huella de carbono", () => {
  it("muestra los kg CO₂e, que es una estimación, y el reparto por pedido", async () => {
    renderCard();

    expect(await screen.findByText(/^112,5 kg CO₂e$/)).toBeInTheDocument();
    expect(screen.getByText("Estimado")).toBeInTheDocument();
    expect(screen.getByText(/OT-0001 · Agroexportadora \(demo\)/)).toBeInTheDocument();
    expect(screen.getByText("90 kg CO₂e · 80 %")).toBeInTheDocument();
  });

  it("sin factor dice «Sin datos» y nombra el tipo, nunca un cero", async () => {
    body = { ...CALCULATED, status: "NO_FACTOR", kgCo2e: null, factorKgCo2ePerKm: null, orders: [] };
    renderCard();

    expect(await screen.findByText("Sin datos")).toBeInTheDocument();
    expect(screen.getByText(/CAMION-10T no tiene factor de emisión/)).toBeInTheDocument();
    expect(screen.queryByText(/^0 kg/)).not.toBeInTheDocument();
  });
});
