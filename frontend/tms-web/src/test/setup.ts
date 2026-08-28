import { expect } from 'vitest'
import * as axeMatchers from 'vitest-axe/matchers'

// Los matchers de accesibilidad se registran una vez, aquí, en lugar de importarse en cada
// fichero de pruebas (JOB 26). El import de conveniencia de vitest-axe no se engancha con
// esta versión de Vitest, y un `expect.extend` explícito no depende de ese detalle.
expect.extend(axeMatchers)

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
