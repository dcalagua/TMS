import { expect, test } from '@playwright/test'
import { TEST_USER, signIn, stubServices } from './support/app'

/**
 * The regression guard for the defect this remediation started from:
 *
 *   FIRST_LOGIN_REQUIRES_SECOND_ATTEMPT
 *
 * A user signed in with correct credentials, reached the authenticated area for an instant,
 * one backend call answered 401, and the app signed them straight back out. The second attempt
 * then worked. These tests fail if that behaviour ever returns.
 */

test.describe('first sign-in', () => {
  test('one sign-in is enough: the dashboard stays authenticated', async ({ page }) => {
    const counters = await stubServices(page)

    await signIn(page)

    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()
    await expect(page).toHaveURL(/\/$/)
    // Still on the dashboard a moment later - not bounced back to the login form.
    await page.waitForTimeout(600)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Ingresar' })).toHaveCount(0)
    expect(counters.signInCalls, 'the user had to authenticate more than once').toBe(1)
  })

  test('survives a transient 401 on its very first backend call', async ({ page }) => {
    // Exactly the original failure: the first authenticated request is refused. The app must
    // recover it rather than tear down a session that was just created.
    const counters = await stubServices(page, { initialUnauthorizedCalls: 1 })

    await signIn(page)

    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Ingresar' })).toHaveCount(0)
    expect(counters.signInCalls).toBe(1)
    expect(counters.refreshCalls, 'the session should be refreshed once, not repeatedly').toBeLessThanOrEqual(1)
  })

  test('never flashes the authenticated shell and then returns to the login form', async ({ page }) => {
    await stubServices(page, { initialUnauthorizedCalls: 1 })
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    // Poll for a second: the old behaviour reached the dashboard and only then bounced back,
    // so a single assertion right after sign-in would have passed against the defect.
    for (let sample = 0; sample < 10; sample += 1) {
      await page.waitForTimeout(100)
      expect(new URL(page.url()).pathname, 'the app returned to the login screen').toBe('/')
      await expect(page.getByRole('button', { name: 'Ingresar' })).toHaveCount(0)
    }
  })

  test('rejects the wrong password without signing anyone in', async ({ page }) => {
    await stubServices(page)

    await signIn(page, 'definitely-wrong')

    await expect(page.getByRole('alert')).toContainText(/Invalid login credentials|credenciales/i)
    await expect(page).toHaveURL(/\/login$/)
  })
})

test.describe('session', () => {
  test('persists across a reload', async ({ page }) => {
    await stubServices(page)
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    await page.reload()

    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()
    await expect(page).not.toHaveURL(/\/login/)
  })

  test('survives opening a deep link directly', async ({ page }) => {
    await stubServices(page)
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    await page.goto('/fleet/vehicles')

    await expect(page.getByRole('heading', { level: 1, name: 'Vehículos' })).toBeVisible()
  })

  test('signs out on request and allows signing in again', async ({ page }) => {
    const counters = await stubServices(page)
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    await page.getByRole('button', { name: new RegExp(TEST_USER.email) }).click()
    await page.getByRole('menuitem', { name: 'Cerrar sesión' }).click()
    // SweetAlert2 confirmation.
    await page.getByRole('button', { name: 'Cerrar sesión' }).last().click()

    await expect(page.getByRole('button', { name: 'Ingresar' })).toBeVisible()

    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()
    expect(counters.signInCalls).toBe(2)
  })
})
