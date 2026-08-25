/**
 * Arranque común de Vitest, ya declarado por `vite.config.ts` (`test.setupFiles`).
 *
 * Hace dos cosas y ninguna más: añadir los matchers de jest-dom y desmontar lo que un test haya
 * renderizado. `globals: false` en la configuración obliga a importar `describe`/`it`/`expect`
 * en cada fichero, así que aquí no se registra nada implícito salvo el ciclo de limpieza.
 */
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => {
  cleanup();
});
