import { afterEach, describe, expect, it, vi } from "vitest";
import { arrivalPosition } from "./arrivalPosition";

/**
 * La ubicación de «Llegué» (V75) nunca impide la llegada: sin permiso, sin GPS o sin respuesta,
 * devuelve `null` y la llegada se registra sin posición.
 */

/** jsdom no trae geolocalización: cada prueba pone la suya y se quita al terminar. */
function withGeolocation(geolocation: Partial<Geolocation> | undefined) {
  Object.defineProperty(navigator, "geolocation", { configurable: true, value: geolocation });
}

afterEach(() => {
  Reflect.deleteProperty(navigator, "geolocation");
  vi.useRealTimers();
});

describe("la ubicación de la llegada", () => {
  it("devuelve la posición del teléfono redondeada a seis decimales", async () => {
    withGeolocation({
      getCurrentPosition: (ok: PositionCallback) => ok({ coords: { latitude: -2.1709987, longitude: -79.9223591 } } as GeolocationPosition),
    });

    await expect(arrivalPosition()).resolves.toEqual({ latitude: -2.170999, longitude: -79.922359 });
  });

  it("sin permiso devuelve null", async () => {
    withGeolocation({
      getCurrentPosition: (_ok: PositionCallback, fail?: PositionErrorCallback | null) =>
        fail?.({ code: 1, message: "denied" } as GeolocationPositionError),
    });

    await expect(arrivalPosition()).resolves.toBeNull();
  });

  it("sin geolocalización en el navegador devuelve null", async () => {
    withGeolocation(undefined);

    await expect(arrivalPosition()).resolves.toBeNull();
  });

  it("si el diálogo de permiso nunca se contesta, deja de esperar a los seis segundos", async () => {
    vi.useFakeTimers();
    withGeolocation({ getCurrentPosition: () => {} });

    const pending = arrivalPosition();
    await vi.advanceTimersByTimeAsync(6_000);

    await expect(pending).resolves.toBeNull();
  });
});
