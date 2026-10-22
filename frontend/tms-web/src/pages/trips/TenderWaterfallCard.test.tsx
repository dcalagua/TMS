import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TenderWaterfallView } from "../../shared/api/tendersApi";
import { TenderWaterfallCard } from "./TenderWaterfallCard";

/**
 * ITEM 10: la tarjeta de la cascada dice cuándo la instalación avanza sola, y sólo entonces. Con el
 * avance apagado (el defecto) la tarjeta sigue pidiendo a una persona que avance.
 */

function waterfall(overrides: Partial<TenderWaterfallView>): TenderWaterfallView {
  return {
    id: "w-1", tripId: "trip-1", status: "ACTIVE", maxAttempts: 4, responseMinutes: 120, attemptsUsed: 1,
    currentOfferLapsed: true, autoAdvance: false, outcomeNote: null, startedAt: "2026-10-15T10:00:00Z",
    completedAt: null, candidates: [],
    ...overrides,
  };
}

function renderCard(view: TenderWaterfallView) {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(view), {
    status: 200, headers: { "content-type": "application/json" },
  })));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <TenderWaterfallCard companyId="company-1" tripId="trip-1" canManage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.restoreAllMocks();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("TenderWaterfallCard", () => {
  it("muestra «Avance automático» y lo explica cuando la instalación avanza sola", async () => {
    renderCard(waterfall({ autoAdvance: true }));

    expect(await screen.findByText("Avance automático")).toBeInTheDocument();
    expect(screen.getByText(/el avance automático la pasará al siguiente transportista/i)).toBeInTheDocument();
    // El botón sigue: no hay por qué esperar a la próxima vuelta.
    expect(screen.getByRole("button", { name: /avanzar/i })).toBeInTheDocument();
  });

  it("con el avance apagado no promete nada y pide a una persona que avance", async () => {
    renderCard(waterfall({ autoAdvance: false }));

    expect(await screen.findByText(/avanza para ofrecer al siguiente transportista/i)).toBeInTheDocument();
    expect(screen.queryByText("Avance automático")).not.toBeInTheDocument();
  });

  it("una cascada terminada no lleva el chip aunque la instalación avance sola", async () => {
    renderCard(waterfall({ autoAdvance: true, status: "ACCEPTED", currentOfferLapsed: false }));

    await screen.findByText(/ofertas hechas/i);
    expect(screen.queryByText("Avance automático")).not.toBeInTheDocument();
  });
});
