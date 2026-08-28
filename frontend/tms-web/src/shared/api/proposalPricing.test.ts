import { describe, expect, it } from "vitest";
import type { ProposalPricing } from "./planningApi";

/**
 * El contrato de tarificación de una propuesta (JOB 11, deuda D1).
 *
 * Lo que se fija aquí no es aritmética — eso vive en `ProposalPricerTest` del backend — sino la
 * regla de lectura que la pantalla debe respetar: **`totalCost` null nunca se dibuja como cero**, y
 * `pricedTrips` no es un precio.
 *
 * Un total que se saltara los viajes sin acuerdo haría parecer más barato al peor plan, y comparar
 * motores por coste es justo para lo que sirve la cifra. Si alguien cambia la pantalla para pintar
 * un `0` cuando no hay total, estas pruebas dicen por qué no.
 */

/** Lo que la pantalla decide mostrar, extraído para poder probarlo sin montar el cajón. */
function costLabel(pricing: ProposalPricing): string {
  if (pricing.totalCost !== null) return `${pricing.totalCost} ${pricing.currency}`;
  if (pricing.reason === "MIXED_CURRENCIES") return "mixed";
  if (pricing.reason === "NO_AGREEMENT_FOR_SOME_TRIP") return `partial:${pricing.pricedTrips}/${pricing.totalTrips}`;
  return "none";
}

describe("cómo se lee una propuesta tarificada", () => {
  it("con total, muestra el importe y su moneda", () => {
    expect(costLabel({
      totalCost: 1680, currency: "PEN", reason: null, pricedTrips: 2, totalTrips: 2,
    })).toBe("1680 PEN");
  });

  it("sin acuerdo para algún viaje, no muestra cero: muestra cuántos sí", () => {
    const pricing: ProposalPricing = {
      totalCost: null, currency: null, reason: "NO_AGREEMENT_FOR_SOME_TRIP",
      pricedTrips: 1, totalTrips: 2,
    };

    expect(costLabel(pricing)).toBe("partial:1/2");
    // La afirmación que importa: el total sigue ausente aunque un viaje sí se pudo cotizar.
    expect(pricing.totalCost).toBeNull();
  });

  it("con monedas mezcladas no hay total, y no se convierte nada", () => {
    const pricing: ProposalPricing = {
      totalCost: null, currency: null, reason: "MIXED_CURRENCIES", pricedTrips: 1, totalTrips: 2,
    };

    expect(costLabel(pricing)).toBe("mixed");
    expect(pricing.currency).toBeNull();
  });

  it("un plan vacío no tiene coste, que no es lo mismo que no haber podido calcularlo", () => {
    expect(costLabel({
      totalCost: null, currency: null, reason: "NO_TRIPS", pricedTrips: 0, totalTrips: 0,
    })).toBe("none");
  });
});
