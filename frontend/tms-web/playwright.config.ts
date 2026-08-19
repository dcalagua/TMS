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
  workers: process.env.CI ? 2 : undefined,
  reporter: [['list'], ['html', { outputFolder: './artifacts/playwright-report', open: 'never' }]],
  timeout: 30_000,
  expect: { timeout: 7_000 },
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
