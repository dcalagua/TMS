import { createHash } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { defineConfig } from 'vitest/config'
import type { Plugin } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Sella `dist/sw.js` con la huella del build y con los ficheros que debe precachear (ADR-019 §1).
 *
 * <h2>Por qué no basta con la caché en tiempo de ejecución</h2>
 * Un service worker nuevo puede activarse en el primer arranque SIN señal (las pestañas viejas ya
 * se cerraron). Al activarse borra las cachés del build anterior, y su `index.html` apunta a
 * ficheros con hash nuevos que nadie ha descargado todavía: pantalla en blanco justo donde el
 * portal tiene que funcionar. Por eso instala ya con la entrada de la app y las pantallas del
 * portal (y lo que estas importan), no con todo: Reportes o Planificación no hacen falta en ruta.
 *
 * <p>La huella cambia `sw.js` en cada despliegue, que es lo que hace que el navegador vea una
 * versión nueva y la app ofrezca «Nueva versión disponible».
 */
function serviceWorkerBuildStamp(): Plugin {
  let precache: string[] = []
  let buildId = 'dev'
  return {
    name: 'etms-sw-build-id',
    apply: 'build',
    generateBundle(_options, bundle) {
      const chunks = Object.values(bundle).filter((item) => item.type === 'chunk')
      const byName = new Map(chunks.map((chunk) => [chunk.fileName, chunk]))
      const wanted = new Set<string>()
      const visit = (fileName: string) => {
        if (wanted.has(fileName)) return
        const chunk = byName.get(fileName)
        if (!chunk) return
        wanted.add(fileName)
        chunk.viteMetadata?.importedCss.forEach((css) => wanted.add(css))
        chunk.imports.forEach(visit)
      }
      chunks
        .filter((chunk) => chunk.isEntry || chunk.moduleIds.some((id) => id.includes('/src/pages/portal/')))
        .forEach((chunk) => visit(chunk.fileName))
      precache = [...wanted].sort().map((fileName) => `/${fileName}`)
      buildId = createHash('sha256').update(Object.keys(bundle).sort().join('\n')).digest('hex').slice(0, 12)
    },
    writeBundle(options) {
      const file = join(options.dir ?? 'dist', 'sw.js')
      if (!existsSync(file)) return
      const source = readFileSync(file, 'utf8')
        .replace('"__ETMS_BUILD_ID__"', JSON.stringify(buildId))
        .replace('[] /* __ETMS_PRECACHE__ */', JSON.stringify(precache))
      writeFileSync(file, source)
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), serviceWorkerBuildStamp()],
  server: {
    // El backend se alcanza por URL absoluta (VITE_API_BASE_URL), no por proxy de desarrollo,
    // para que local y desplegado ejerciten exactamente el mismo camino de código.
    port: 5173,
    strictPort: false,
  },
  /**
   * Las dependencias que solo se alcanzan desde una ruta perezosa, declaradas a mano.
   *
   * <h2>El fallo que esto cierra</h2>
   * Al arrancar, Vite pre-empaqueta las dependencias que encuentra recorriendo el grafo desde
   * `index.html`. `recharts` y `@googlemaps/js-api-loader` no están en ese grafo: cuelgan de
   * pantallas que se cargan con `lazy()`, así que Vite no los ve hasta que alguien abre
   * Destinos o Reportes por primera vez. Ahí lanza una optimización nueva, cambia el `?v=` de
   * TODAS las dependencias y devuelve `504 (Outdated Optimize Dep)` a las peticiones que ya
   * estaban en vuelo con el hash viejo — que es justo el `import()` de la pantalla que acaba de
   * pedirse. El resultado es «Failed to fetch dynamically imported module» al abrir la pantalla,
   * y se arregla recargando, que no es un arreglo.
   *
   * <p>Declaradas aquí entran en la primera pasada, con el resto: no hay segunda optimización y
   * por tanto no hay hash que se invalide a mitad de sesión.
   *
   * <p>Solo afecta al servidor de desarrollo. `vite build` recorre el grafo entero, incluidos
   * los `import()`, y nunca tuvo este problema.
   */
  optimizeDeps: {
    include: ['recharts', '@googlemaps/js-api-loader'],
  },
  build: {
    outDir: 'dist',
    // No source maps in the production build, because nothing consumes them and the artefact is
    // public. `true` handed the full source of a private product to anyone who opened dev tools.
    // 'hidden' was the first attempt and was not enough: it only drops the //# sourceMappingURL
    // comment. The maps are still written next to each chunk in dist/assets, amplify.yml
    // publishes dist with `files: '**/*'`, and every chunk name is public in index.html - so the
    // full source stayed one request away at `<chunk>.js.map`. Hiding the pointer is not hiding
    // the file.
    //
    // If an error-tracking service is ever added, generate maps only for that service: build
    // with maps, upload them from the pipeline, then delete every *.map from dist before the
    // artefact is published - never ship them inside it. Until then, `false`.
    sourcemap: false,
  },
  test: {
    environment: 'jsdom',
    globals: false,
    // MUI renderiza a través de Emotion, que inyecta estilos por componente y hace que un
    // montaje pese bastante más que un markup plano. Los 5s por defecto de Vitest se quedan
    // cortos con varios workers compartiendo máquina.
    testTimeout: 20_000,
    hookTimeout: 20_000,
    setupFiles: ['./src/test/setup.ts'],
    // Las fechas se dibujan en el huso del navegador, y los fixtures están escritos en hora de
    // Lima. Sin fijarlo, la suite pasa en una máquina de Lima y falla en el CI, que corre en UTC.
    env: { TZ: 'America/Lima' },
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
})
