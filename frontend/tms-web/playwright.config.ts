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
  // 12s rather than 7s because every screen is now code-split: the first visit to a route in a
  // run asks the dev server for a chunk it has not transformed yet, and with several workers
  // asking for different ones at once that transform queues behind the others. It is a
  // development-server cost and not a product one - a built bundle serves those chunks from disk -
  // but the suite runs against the dev server, so the budget has to cover it. Still short enough
  // that a screen which genuinely never renders fails the test rather than hanging it.
  expect: { timeout: 12_000 },
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
