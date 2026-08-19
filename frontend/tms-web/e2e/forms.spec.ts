import { expect, test } from '@playwright/test'
import { horizontalOverflow, signIn, stubServices } from './support/app'

/**
 * The form dialogs, checked in a real browser: they must fit every viewport the brief lists and
 * stay operable by touch. Below `sm` a dialog fills the screen rather than floating inside a
 * scrolling page, which is what the assertions below encode.
 */

const VIEWPORTS = [
  { name: '320x568', width: 320, height: 568 },
  { name: '360x800', width: 360, height: 800 },
  { name: '390x844', width: 390, height: 844 },
  { name: '768x1024', width: 768, height: 1024 },
  { name: '1024x768', width: 1024, height: 768 },
  { name: '1366x768', width: 1366, height: 768 },
] as const

/** One dialog per module, opened from its own screen's primary action. */
const FORMS = [
  { path: '/masters/origins', open: 'Nuevo origen', title: 'Nuevo origen' },
  { path: '/masters/destinations', open: 'Nuevo destino', title: 'Nuevo destino' },
  { path: '/masters/zones', open: 'Nueva zona', title: 'Nueva zona' },
  { path: '/masters/frequencies', open: 'Nueva frecuencia', title: 'Nueva frecuencia' },
  { path: '/fleet/carriers', open: 'Nuevo transportista', title: 'Nuevo transportista' },
  { path: '/fleet/vehicle-types', open: 'Nuevo tipo de vehículo', title: 'Nuevo tipo de vehículo' },
] as const

/** Touch targets below this are hard to hit reliably on a phone. */
const MIN_TOUCH_TARGET = 32

// Each of these opens six dialogs in turn against the dev server; the default 30s is tight
// once several viewports run in parallel.
test.describe.configure({ timeout: 90_000 })

for (const viewport of VIEWPORTS) {
  test(`form dialogs fit and stay operable at ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await stubServices(page)
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    for (const form of FORMS) {
      await page.goto(form.path)
      await page.getByRole('button', { name: form.open }).click()

      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()
      await expect(dialog).toHaveAttribute('aria-modal', 'true')
      await expect(dialog).toHaveAccessibleName(form.title)

      expect(await horizontalOverflow(page), `${form.path} at ${viewport.name}`).toBeLessThanOrEqual(0)

      // The dialog itself must not be wider than the viewport either.
      const box = await dialog.boundingBox()
      expect(box?.width ?? 0, `${form.path} dialog width at ${viewport.name}`).toBeLessThanOrEqual(viewport.width)

      const save = dialog.getByRole('button', { name: 'Guardar' })
      await expect(save).toBeVisible()
      const saveBox = await save.boundingBox()
      expect(saveBox?.height ?? 0, `${form.path} save button height`).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET)

      await page.keyboard.press('Escape')
      await expect(dialog).toBeHidden()
    }
  })
}

test('a dialog traps focus, returns it to the trigger, and locks the page behind', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  await stubServices(page)
  await signIn(page)
  await page.goto('/masters/origins')

  const trigger = page.getByRole('button', { name: 'Nuevo origen' })
  await trigger.click()

  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()

  // Focus starts inside the dialog and stays there while tabbing.
  for (let press = 0; press < 15; press += 1) {
    await page.keyboard.press('Tab')
    const inside = await page.evaluate(() => {
      const element = document.querySelector('[role="dialog"]')
      return element ? element.contains(document.activeElement) : false
    })
    expect(inside).toBe(true)
  }

  expect(await page.evaluate(() => document.body.style.overflow)).toBe('hidden')

  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(trigger).toBeFocused()
  expect(await page.evaluate(() => document.body.style.overflow)).not.toBe('hidden')
})

test('a dialog reports validation inline, in the active language', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  await stubServices(page)
  await signIn(page)
  await page.goto('/masters/zones')

  await page.getByRole('button', { name: 'Nueva zona' }).click()
  await page.getByRole('button', { name: 'Guardar' }).click()

  await expect(page.getByText('Este campo es obligatorio').first()).toBeVisible()

  // The language switch lives in the top bar, which a modal correctly covers, so the language
  // is changed with the dialog closed and the dialog reopened.
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toBeHidden()
  await page.getByRole('button', { name: 'EN', exact: true }).click()

  await page.getByRole('button', { name: 'New zone' }).click()
  await expect(page.getByRole('dialog')).toHaveAccessibleName('New zone')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByText('This field is required').first()).toBeVisible()
})
