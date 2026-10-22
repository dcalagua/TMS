import { vi } from "vitest";

/**
 * Un teléfono de `width` píxeles de ancho (390 = un iPhone 12-15) para las pruebas del portal.
 *
 * jsdom no maqueta, pero MUI decide la forma de una pantalla con `useMediaQuery`, que lee
 * `window.matchMedia`, y jsdom no lo implementa: sin esto `useMediaQuery` devuelve siempre `false`
 * y la rama del teléfono se prueba por casualidad. Aquí la consulta se evalúa de verdad contra el
 * ancho dado (`min-width` / `max-width` en px), así que una hoja que en escritorio sale por la
 * derecha y en el teléfono por abajo se prueba en la forma del teléfono. Se deshace con
 * `vi.unstubAllGlobals()` / `vi.restoreAllMocks()`.
 */
export function emulatePhoneViewport(width = 390): void {
  vi.stubGlobal("innerWidth", width);
  vi.stubGlobal("matchMedia", (query: string): MediaQueryList => {
    const min = /min-width:\s*([\d.]+)px/.exec(query);
    const max = /max-width:\s*([\d.]+)px/.exec(query);
    const matches = (!min || width >= Number(min[1])) && (!max || width <= Number(max[1]));
    return {
      matches, media: query, onchange: null,
      addListener: () => undefined, removeListener: () => undefined,
      addEventListener: () => undefined, removeEventListener: () => undefined,
      dispatchEvent: () => false,
    };
  });
}
