import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { AvailabilityDrawer } from "./AvailabilityDrawer";

vi.mock("../../shared/api/fleetAvailabilityApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../shared/api/fleetAvailabilityApi")>();
  return {
    ...actual,
    listVehicleUnavailability: vi.fn(async () => []),
    listDriverUnavailability: vi.fn(async () => []),
  };
});

/**
 * El cajón de disponibilidad (migración V42).
 *
 * Lo que se protege es la regla que el backend ya impone y que la pantalla no debe contradecir:
 * **cada motivo describe un tipo de recurso**. Un camión de vacaciones y un conductor en reparación
 * son ambos absurdos, y el servidor los rechaza - así que ofrecerlos en el desplegable sería
 * ofrecer una opción que sólo puede fallar.
 *
 * Es exactamente la clase de regresión que un typechecker no ve: las dos listas son `string[]`.
 */

const COMPANY = "11111111-1111-4111-8111-111111111111";

function drawer(resource: "vehicle" | "driver") {
  return render(
    <AvailabilityDrawer
      companyId={COMPANY}
      resource={resource}
      resourceId="22222222-2222-4222-8222-222222222222"
      resourceLabel={resource === "vehicle" ? "TR-04 · ABC-123" : "DR-09 · Ana Ríos"}
      canManage
      onClose={() => {}}
    />,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("el cajón de disponibilidad", () => {
  it("para un vehículo ofrece mantenimiento y no vacaciones", () => {
    drawer("vehicle");

    const select = screen.getByLabelText("Motivo");
    expect(select).toBeInTheDocument();
    // El valor por defecto es el primero de la lista del recurso, no una mezcla de las dos.
    expect(screen.getByText("Mantenimiento")).toBeInTheDocument();
    expect(screen.queryByText("Vacaciones")).not.toBeInTheDocument();
  });

  it("para un conductor ofrece ausencia y no reparación", () => {
    drawer("driver");

    expect(screen.getByText("Ausencia")).toBeInTheDocument();
    expect(screen.queryByText("Reparación")).not.toBeInTheDocument();
  });

  it("titula según el recurso, para que nadie registre una baja en el camión equivocado", () => {
    drawer("driver");

    expect(screen.getByText("Disponibilidad del conductor")).toBeInTheDocument();
    expect(screen.getByText("DR-09 · Ana Ríos")).toBeInTheDocument();
  });

  /**
   * El aviso que impide configurar esto esperando otra cosa. ADR-007 dice que una posición informa
   * y no mueve ningún ciclo de vida, y una pantalla que no lo dijera dejaría a alguien creyendo que
   * ha activado detección automática de llegadas.
   */
  it("explica que una ventana no la registra nadie automáticamente", () => {
    drawer("vehicle");

    expect(screen.getByText(/Registrar una ventana/)).toBeInTheDocument();
  });
});
