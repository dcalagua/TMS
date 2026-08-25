import { expect, test, type Locator, type Page } from '@playwright/test'
import { horizontalOverflow, signIn, stubServices } from './support/app'
import { stubLocations } from './support/masters'

/**
 * The right-side drawer as the product's CRUD surface, checked end to end.
 *
 * `forms.spec.ts` already proves the dialog contract at every viewport in the brief. What is
 * asserted here is what makes a drawer a drawer rather than a modal that happens to be tall:
 * it is anchored to the right edge, the list it came from stays behind it, saving refreshes
 * that list through the query cache instead of reloading the page, and unsaved edits are never
 * discarded without asking.
 */

const DESKTOP = { width: 1366, height: 768 }
const MOBILE = { width: 390, height: 844 }

/** Marks the document so a full page reload is detectable: `window.location.reload()` clears it. */
async function markDocument(page: Page) {
  await page.evaluate(() => {
    ;(window as unknown as { __tmsNoReload?: boolean }).__tmsNoReload = true
  })
}

async function documentSurvived(page: Page): Promise<boolean> {
  return page.evaluate(() => (window as unknown as { __tmsNoReload?: boolean }).__tmsNoReload === true)
}

/**
 * Waits for the slide-in to finish. `boundingBox()` reads the box at whatever moment it is
 * called, so measuring a panel mid-animation reports it as partly off-screen.
 */
async function settled(drawer: Locator): Promise<void> {
  await drawer.evaluate(async (element) => {
    await Promise.all(element.getAnimations().map((animation) => animation.finished))
  })
}

async function openOrigins(page: Page, viewport: { width: number; height: number }) {
  await page.setViewportSize(viewport)
  await stubServices(page)
  await stubLocations(page)
  await signIn(page)
  await page.goto('/masters/origins')
  await expect(page.getByRole('heading', { level: 1, name: 'Orígenes' })).toBeVisible()
}

test('the create drawer is anchored to the right edge and leaves the list visible behind it', async ({ page }) => {
  await openOrigins(page, DESKTOP)
  await expect(page.getByText('LIM-CD1')).toBeVisible()

  await page.locator('.tms-page-actions').getByRole('button', { name: 'Nuevo origen' }).click()

  const drawer = page.getByRole('dialog')
  await expect(drawer).toBeVisible()
  await expect(drawer).toHaveAccessibleName('Nueva ubicación')
  await settled(drawer)

  const box = await drawer.boundingBox()
  expect(box).not.toBeNull()
  const layoutWidth = await page.evaluate(() => document.documentElement.clientWidth)
  // Flush against the right edge...
  expect(Math.abs((box!.x + box!.width) - layoutWidth)).toBeLessThanOrEqual(2)
  // ...and short of the left one, so the list is still on screen rather than replaced.
  expect(box!.x).toBeGreaterThan(200)
  // Full height: a panel, not a centred card.
  expect(box!.height).toBeGreaterThanOrEqual(DESKTOP.height - 2)

  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
})

test('creating an origin closes the drawer and refreshes the table without reloading the page', async ({ page }) => {
  await openOrigins(page, DESKTOP)
  await markDocument(page)

  await page.locator('.tms-page-actions').getByRole('button', { name: 'Nuevo origen' }).click()
  const drawer = page.getByRole('dialog')

  await drawer.getByLabel(/^Código/).fill('TRU-CD2')
  await drawer.getByLabel(/^Nombre/).fill('Centro de distribución Trujillo')
  await drawer.getByLabel(/^Zona horaria/).fill('America/Lima')
  await drawer.getByRole('button', { name: 'Guardar' }).click()

  await expect(drawer).toBeHidden()
  // The row is in the table because the list refetched, not because the browser reloaded.
  await expect(page.getByText('TRU-CD2')).toBeVisible()
  expect(await documentSurvived(page)).toBe(true)
})

test('editing an origin opens the same drawer prefilled and writes the change back to the list', async ({ page }) => {
  await openOrigins(page, DESKTOP)
  await markDocument(page)

  const row = page.getByRole('row', { name: /AQP-PL1/ })
  await row.getByRole('button', { name: 'Abrir menú de acciones' }).click()
  await page.getByRole('menuitem', { name: 'Editar' }).click()

  const drawer = page.getByRole('dialog')
  await expect(drawer).toHaveAccessibleName('Editar ubicación')
  await expect(drawer.getByLabel(/^Código/)).toHaveValue('AQP-PL1')

  await drawer.getByLabel(/^Nombre/).fill('Planta Arequipa Sur')
  await drawer.getByRole('button', { name: 'Guardar' }).click()

  await expect(drawer).toBeHidden()
  await expect(page.getByText('Planta Arequipa Sur')).toBeVisible()
  expect(await documentSurvived(page)).toBe(true)
})

test('a drawer with unsaved edits asks before discarding them', async ({ page }) => {
  await openOrigins(page, DESKTOP)

  await page.locator('.tms-page-actions').getByRole('button', { name: 'Nuevo origen' }).click()
  const drawer = page.getByRole('dialog')
  await drawer.getByLabel(/^Código/).fill('TEMP-1')

  // Escape is a soft dismiss: it must not throw the typing away silently.
  await page.keyboard.press('Escape')
  const confirmation = page.locator('.swal2-popup')
  await expect(confirmation).toBeVisible()
  await expect(confirmation.getByText('¿Descartar cambios?')).toBeVisible()

  await confirmation.getByRole('button', { name: 'Seguir editando' }).click()
  await expect(drawer).toBeVisible()
  await expect(drawer.getByLabel(/^Código/)).toHaveValue('TEMP-1')

  await page.keyboard.press('Escape')
  await page.locator('.swal2-popup').getByRole('button', { name: 'Descartar' }).click()
  await expect(drawer).toBeHidden()
})

test('a clean drawer closes without asking anything', async ({ page }) => {
  await openOrigins(page, DESKTOP)

  await page.locator('.tms-page-actions').getByRole('button', { name: 'Nuevo origen' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()

  await page.keyboard.press('Escape')

  await expect(page.getByRole('dialog')).toBeHidden()
  await expect(page.locator('.swal2-popup')).toBeHidden()
})

test('the drawer takes the whole screen on a phone and still scrolls only inside itself', async ({ page }) => {
  await openOrigins(page, MOBILE)

  await page.locator('.tms-page-actions').getByRole('button', { name: 'Nuevo origen' }).click()
  const drawer = page.getByRole('dialog')
  await expect(drawer).toBeVisible()
  await settled(drawer)

  const box = await drawer.boundingBox()
  const layoutWidth = await page.evaluate(() => document.documentElement.clientWidth)
  expect(box?.width ?? 0).toBeCloseTo(layoutWidth, 0)
  expect(box?.x ?? -1).toBeCloseTo(0, 0)

  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
  expect(await page.evaluate(() => document.body.style.overflow)).toBe('hidden')

  // The footer actions stay on screen no matter how far down the body is scrolled.
  await drawer.getByRole('button', { name: 'Guardar' }).scrollIntoViewIfNeeded()
  await expect(drawer.getByRole('button', { name: 'Guardar' })).toBeInViewport()
})
