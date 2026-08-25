import { defineConfig, devices } from "@playwright/test";

/**
 * E2E contra el bundle construido, no contra el servidor de desarrollo.
 *
 * Es la misma decisión que tomó el commit a785eb6 antes de que la suite se perdiera: `vite dev`
 * sirve ESM sin empaquetar y transforma cada módulo la primera vez que se pide, así que una suite
 * que visita nueve pantallas hace al servidor rehacer el grafo de rutas una y otra vez mientras
 * dos workers compiten por él. `vite preview` sirve de disco exactamente los ficheros que se
 * despliegan: más rápido, y ejercita lo que de verdad sale a producción.
 *
 * El puerto es propio (4183) para no chocar con un `preview` que alguien tenga abierto a mano.
 */
const PORT = Number(process.env.E2E_PORT ?? 4183);
const BASE_URL = process.env.E2E_BASE_URL ?? `http://localhost:${PORT}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
  timeout: 30_000,
  expect: { timeout: 7_000 },
  use: {
    baseURL: BASE_URL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: `npm run build && npx vite preview --port ${PORT} --strictPort`,
        url: BASE_URL,
        reuseExistingServer: !process.env.CI,
        timeout: 180_000,
      },
});
