import 'vitest'
import type { AxeResults } from 'axe-core'

/**
 * El tipo del matcher que `src/test/setup.ts` registra (JOB 26).
 *
 * Hace falta porque `expect.extend` añade el matcher en tiempo de ejecución y TypeScript no lo ve.
 * Lo encontró `npm run build` y no `tsc -p tsconfig.app.json`, que no cubre los ficheros de prueba —
 * la misma lección que dejó el JOB 19, y la razón de que el build real siga siendo obligatorio.
 */
declare module 'vitest' {
  interface Assertion<T = unknown> {
    toHaveNoViolations(): T
  }
  interface AsymmetricMatchersContaining {
    toHaveNoViolations(): AxeResults
  }
}
