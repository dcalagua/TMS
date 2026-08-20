import { expect, test } from '@playwright/test'
import { signIn, stubServices } from './support/app'

/**
 * The Google Maps location picker, checked without a real API key.
 *
 * This dev/CI environment has no `VITE_GOOGLE_MAPS_API_KEY` configured (see
 * `docs/integrations/GOOGLE_MAPS.md`), which is exactly the state every deployment starts in
 * before someone provisions and restricts a key in Google Cloud Console. What matters is that
 * the Location drawer never blocks on it: the map area degrades to a plain notice and the
 * existing manual latitude/longitude fields keep working underneath it.
 *
 * A second suite tagged `@google-maps-live` would drive the real picker against a provisioned
 * key; it is intentionally not included here because this environment does not have one (see the
 * "Testing" section of the doc above).
 */

test('the location drawer degrades to manual coordinate entry when no Google Maps key is configured', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  await stubServices(page)
  await signIn(page)
  await page.goto('/masters/locations')
  await expect(page.getByRole('heading', { level: 1, name: 'Ubicaciones' })).toBeVisible()

  await page.locator('.tms-page-actions').getByRole('button', { name: 'Nueva ubicación' }).click()
  const drawer = page.getByRole('dialog')
  await expect(drawer).toBeVisible()

  // No map canvas, no search box - just the non-blocking notice.
  await expect(drawer.getByRole('application')).toHaveCount(0)
  await expect(drawer.getByText('El mapa no está disponible. Puedes ingresar la latitud y la longitud manualmente.'))
    .toBeVisible()

  // The rest of the form, including manual coordinates, is unaffected.
  await drawer.getByLabel(/^Código/).fill('MAP-1')
  await drawer.getByLabel(/^Nombre/).fill('Sin mapa')
  await drawer.getByLabel(/^Latitud/).fill('-12.046374')
  await drawer.getByLabel(/^Longitud/).fill('-77.042793')
  await expect(drawer.getByLabel(/^Latitud/)).toHaveValue('-12.046374')
  await expect(drawer.getByLabel(/^Longitud/)).toHaveValue('-77.042793')
})

test('the exact-coordinates section is an open-by-default disclosure, not the primary UX', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  await stubServices(page)
  await signIn(page)
  await page.goto('/masters/locations')

  await page.locator('.tms-page-actions').getByRole('button', { name: 'Nueva ubicación' }).click()
  const drawer = page.getByRole('dialog')

  const details = drawer.locator('details.tms-details-compact')
  await expect(details).toBeVisible()
  await expect(details).toHaveJSProperty('open', true)
  await expect(details.getByText('Coordenadas exactas')).toBeVisible()
  // Collapsing it hides the raw fields without touching the rest of the form.
  await details.locator('summary').click()
  await expect(details).toHaveJSProperty('open', false)
})
