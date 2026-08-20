import { expect, test, type Page } from '@playwright/test'
import { signIn, stubServices } from './support/app'
import { stubLocations } from './support/masters'

/**
 * The Location domain, end to end: one physical record, two operational uses, three screens.
 *
 * This is the behaviour the whole domain change exists for, and it is only observable as a
 * sequence. A store registered as a delivery point must show up in Ubicaciones and in Destinos
 * and *not* in Orígenes; ticking "puede utilizarse como origen" on that same record must make
 * it appear in Orígenes too - without a second row, a second address or a second code. Any
 * design that goes back to two masters fails the last assertion here.
 */

const DESKTOP = { width: 1366, height: 768 }

async function open(page: Page, path: string, heading: string) {
  await page.goto(path)
  await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible()
}

async function rows(page: Page) {
  await expect(page.locator('table tbody')).toBeVisible()
  return page.locator('table tbody tr')
}

test('a store registered as a delivery point can later ship its own returns, as one record', async ({ page }) => {
  await page.setViewportSize(DESKTOP)
  await stubServices(page)
  await stubLocations(page)
  await signIn(page)

  // --- create it in Ubicaciones, as a store that receives -------------------------------
  await open(page, '/masters/locations', 'Ubicaciones')
  await page.locator('.tms-page-actions').getByRole('button', { name: 'Nueva ubicación' }).click()

  const drawer = page.getByRole('dialog')
  await expect(drawer).toBeVisible()
  await drawer.getByLabel(/^Código/).fill('SURCO')
  await drawer.getByLabel(/^Nombre/).fill('Tienda Surco')
  await drawer.getByLabel('Tipo').click()
  await page.getByRole('option', { name: 'Tienda' }).click()

  // A new location arrives able to receive; that is the use this screen is creating.
  await expect(drawer.getByRole('checkbox', { name: 'Puede utilizarse como destino' })).toBeChecked()
  await expect(drawer.getByRole('checkbox', { name: 'Puede utilizarse como origen' })).not.toBeChecked()
  await drawer.getByRole('button', { name: 'Guardar' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // --- it is in the master, with its type and its use kept apart -------------------------
  await expect(page.getByText('SURCO', { exact: true })).toBeVisible()
  const created = (await rows(page)).filter({ hasText: 'SURCO' })
  await expect(created).toHaveCount(1)
  await expect(created.getByText('Tienda', { exact: true })).toBeVisible()
  await expect(created.getByText('Destino', { exact: true })).toBeVisible()
  await expect(created.getByText('Origen', { exact: true })).toHaveCount(0)

  // --- Destinos shows it; Orígenes does not ----------------------------------------------
  await open(page, '/masters/destinations', 'Destinos')
  await expect(page.getByText('SURCO', { exact: true })).toBeVisible()

  await open(page, '/masters/origins', 'Orígenes')
  await expect(page.getByText('LIM-CD1', { exact: true })).toBeVisible()
  await expect(page.getByText('SURCO', { exact: true })).toHaveCount(0)

  // --- tick the origin use on the same record --------------------------------------------
  await open(page, '/masters/locations', 'Ubicaciones')
  await expect(page.getByText('SURCO', { exact: true })).toBeVisible()
  await (await rows(page)).filter({ hasText: 'SURCO' })
    .getByRole('button', { name: 'Abrir menú de acciones' })
    .click()
  await page.getByRole('menuitem', { name: 'Editar' }).click()

  const editor = page.getByRole('dialog')
  await expect(editor).toBeVisible()
  await expect(editor.getByLabel(/^Código/)).toHaveValue('SURCO')
  await editor.getByRole('checkbox', { name: 'Puede utilizarse como origen' }).check()
  await editor.getByRole('button', { name: 'Guardar' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // --- now both lists show it, and the master still holds exactly one row -----------------
  await open(page, '/masters/origins', 'Orígenes')
  await expect(page.getByText('SURCO', { exact: true })).toBeVisible()

  await open(page, '/masters/destinations', 'Destinos')
  await expect(page.getByText('SURCO', { exact: true })).toBeVisible()

  await open(page, '/masters/locations', 'Ubicaciones')
  await expect((await rows(page)).filter({ hasText: 'SURCO' })).toHaveCount(1)
})

test('Origins and Destinations ask the Locations endpoint for one operational use each', async ({ page }) => {
  await page.setViewportSize(DESKTOP)
  await stubServices(page)
  await stubLocations(page)
  await signIn(page)

  // Not a stub detail: the two screens have no endpoint of their own to get wrong. If either
  // ever stopped sending its role, it would silently list the whole master.
  const requested: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname.endsWith('/masterdata/locations')) {
      requested.push(url.searchParams.get('role') ?? 'none')
    }
  })

  await open(page, '/masters/origins', 'Orígenes')
  await expect(page.getByText('LIM-CD1', { exact: true })).toBeVisible()
  expect(requested).toContain('ORIGIN')

  await open(page, '/masters/destinations', 'Destinos')
  await expect(page.getByText('MIRAFLORES', { exact: true })).toBeVisible()
  expect(requested).toContain('DESTINATION')

  // Ubicaciones is the unfiltered master, so it must send no role at all.
  await open(page, '/masters/locations', 'Ubicaciones')
  await expect(page.getByText('MIRAFLORES', { exact: true })).toBeVisible()
  expect(requested).toContain('none')
})

test('the operational use is not offered as a filter on a screen that already is that filter', async ({ page }) => {
  await page.setViewportSize(DESKTOP)
  await stubServices(page)
  await stubLocations(page)
  await signIn(page)

  await open(page, '/masters/locations', 'Ubicaciones')
  await expect(page.getByLabel('Uso operacional')).toBeVisible()

  await open(page, '/masters/origins', 'Orígenes')
  await expect(page.getByLabel('Tipo')).toBeVisible()
  await expect(page.getByLabel('Uso operacional')).toHaveCount(0)
})
