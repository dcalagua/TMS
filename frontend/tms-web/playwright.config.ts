import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end configuration.
 *
 * The suite drives a real browser against the Vite dev server but never against a real
 * Supabase project or a real TMS backend: both are intercepted per test (see
 * `e2e/support/app.ts`). That keeps the run deterministic, keeps real credentials out of the
 * repository, and lets a test script the exact backend behaviour it is about - including the
 * transient 401 that used to destroy a fresh session.
 */
export default defineConfig({
  testDir: './e2e',
  outputDir: './artifacts/playwright',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  // All workers share one Vite dev server; past four they queue on it and slow tests start
  // hitting their timeouts for reasons that have nothing to do with the product.
  workers: process.env.CI ? 2 : 4,
  reporter: [['list'], ['html', { outputFolder: './artifacts/playwright-report', open: 'never' }]],
  timeout: 30_000,
  expect: { timeout: 7_000 },
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  /**
   * A built bundle served by `vite preview`, not the dev server.
   *
   * The dev server serves unbundled ESM and transforms each module on first request. That was
   * merely slow while every screen arrived in one graph at startup; once the routes were
   * code-split, a suite that visits nine screens at six breakpoints made the server transform a
   * fresh route graph over and over, and tests began timing out on `page.goto` - a different two
   * of them on every run, which is the signature of contention rather than of a defect.
   *
   * Preview serves the same files a deployment does, from disk, already bundled. It costs one
   * build up front and removes the whole class of flake, and it means the suite exercises what
   * actually ships rather than a development-only module graph.
   */
  webServer: {
    command: 'npm run build && npm run preview -- --port 5173 --strictPort',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
})
