import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { CarrierView } from "../../shared/api/carriersApi";
import { CarrierFormDrawer } from "./CarrierFormDrawer";

/**
 * El plazo de pago del transportista (fase 65): vacío viaja como `null` - el plazo de la empresa -
 * y no como cero días, que significaría «al contado».
 */

const CARRIER: CarrierView = {
  id: "car-1", code: "ANDINOS", businessName: "Transportes Andinos SAC", taxIdType: "RUC",
  taxIdValue: "20123456789", contactName: null, phone: null, email: null, externalReference: null,
  paymentTermsDays: null, active: true, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z",
};

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn(async () => new Response(JSON.stringify(CARRIER), {
    status: 200, headers: { "content-type": "application/json" },
  }));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function sentBody(): Record<string, unknown> {
  const [, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit];
  return JSON.parse(init.body as string) as Record<string, unknown>;
}

describe("el formulario de transportista", () => {
  it("manda el plazo de pago escrito", async () => {
    const user = userEvent.setup();
    const onSaved = vi.fn();
    render(<CarrierFormDrawer companyId="company-1" carrier={CARRIER} onClose={vi.fn()} onSaved={onSaved} />);

    expect(screen.getByText("Vacío = plazo por defecto de la empresa")).toBeInTheDocument();
    await user.type(screen.getByRole("spinbutton", { name: /Plazo de pago/ }), "45");
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const [input, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit];
    expect(new URL(input).pathname).toMatch(/\/fleet\/carriers\/car-1$/);
    expect(init.method).toBe("PUT");
    expect(sentBody().paymentTermsDays).toBe(45);
  });

  it("vacío viaja como null: el plazo de la empresa", async () => {
    const user = userEvent.setup();
    const onSaved = vi.fn();
    render(<CarrierFormDrawer companyId="company-1" carrier={{ ...CARRIER, paymentTermsDays: 60 }} onClose={vi.fn()} onSaved={onSaved} />);

    const field = screen.getByRole("spinbutton", { name: /Plazo de pago/ });
    expect(field).toHaveValue(60);
    await user.clear(field);
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(sentBody().paymentTermsDays).toBeNull();
  });

  it("rechaza un plazo fuera de 0-365 sin llamar al servidor", async () => {
    const user = userEvent.setup();
    render(<CarrierFormDrawer companyId="company-1" carrier={CARRIER} onClose={vi.fn()} onSaved={vi.fn()} />);

    await user.type(screen.getByRole("spinbutton", { name: /Plazo de pago/ }), "400");
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));

    expect(await screen.findByText("Un número entero de 0 a 365")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
