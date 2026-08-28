import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DeliveryDrawer, type DeliveryValues } from "./DeliveryDrawer";

/**
 * El cajón de entrega, con cantidades (migración V45, deuda D3).
 *
 * Lo que se protege es **la regla que hace honesta a toda la funcionalidad**: dejar los campos en
 * blanco manda `quantities: null`, y eso NO es lo mismo que mandar ceros.
 *
 * La evaluación formal de D3 lo dice sin rodeos: una cantidad entregada no puede inferirse nunca, y
 * "no lo registré" no puede convertirse en "no llegó nada". Un formulario que rellenara ceros por
 * comodidad inventaría un faltante que nadie observó — y se vería como un dato medido.
 *
 * Es exactamente la clase de regresión que un typechecker no ve: `null` y `0` son ambos válidos
 * para el tipo.
 */

function drawer(onSubmit: (values: DeliveryValues) => Promise<void>) {
  return render(
    <DeliveryDrawer
      stopLabel="Parada 1 · Tienda Centro"
      orderNumber="TO-00000001"
      existing={undefined}
      onClose={() => {}}
      onSubmit={onSubmit}
    />,
  );
}

describe("el cajón de entrega", () => {
  it("sin cantidades escritas manda null, no ceros", async () => {
    const onSubmit = vi.fn<(values: DeliveryValues) => Promise<void>>().mockResolvedValue(undefined);
    drawer(onSubmit);

    await userEvent.click(screen.getByRole("button", { name: /Guardar|Registrar/ }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    // La afirmación que importa. null = "no lo estoy diciendo"; 0 = "el cliente no se llevó nada".
    expect(onSubmit.mock.calls[0][0].quantities).toBeNull();
  });

  it("con un peso llevado escrito, manda el bloque completo", async () => {
    const onSubmit = vi.fn<(values: DeliveryValues) => Promise<void>>().mockResolvedValue(undefined);
    drawer(onSubmit);

    await userEvent.type(screen.getByLabelText("Llevado (kg)"), "1000");
    await userEvent.type(screen.getByLabelText("Entregado (kg)"), "700");
    await userEvent.type(screen.getByLabelText("Rechazado (kg)"), "300");
    await userEvent.click(screen.getByRole("button", { name: /Guardar|Registrar/ }));

    const sent = onSubmit.mock.calls[0][0].quantities;
    expect(sent).not.toBeNull();
    expect(sent?.attemptedWeightKg).toBe(1000);
    expect(sent?.deliveredWeightKg).toBe(700);
    expect(sent?.refusedWeightKg).toBe(300);
  });

  it("dice en pantalla que en blanco no es cero", () => {
    drawer(vi.fn().mockResolvedValue(undefined));

    expect(screen.getByText(/En blanco no es cero/)).toBeInTheDocument();
  });

  /**
   * Un aviso, no una defensa: el servidor revalida cada medida por separado. Está aquí para que el
   * operador lo vea antes de mandar, no para sustituir el invariante.
   */
  it("avisa cuando entregado más rechazado supera lo llevado", async () => {
    drawer(vi.fn().mockResolvedValue(undefined));

    await userEvent.type(screen.getByLabelText("Llevado (kg)"), "100");
    await userEvent.type(screen.getByLabelText("Entregado (kg)"), "70");
    await userEvent.type(screen.getByLabelText("Rechazado (kg)"), "40");

    expect(await screen.findByText(/no puede superar lo llevado/)).toBeInTheDocument();
  });
});
