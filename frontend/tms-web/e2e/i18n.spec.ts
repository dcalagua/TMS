import { expect, test } from '@playwright/test'
import { signIn, stubServices } from './support/app'

test.describe('language', () => {
  test('starts in Spanish for a first-time visitor', async ({ page }) => {
    await stubServices(page)
    await page.goto('/login')

    await expect(page.getByRole('button', { name: 'Ingresar' })).toBeVisible()
    await expect(page.getByLabel(/^Correo electrónico/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'ES', exact: true })).toHaveAttribute('aria-pressed', 'true')
  })

  test('ignores an English browser: Spanish is still the first thing shown', async ({ browser }) => {
    const context = await browser.newContext({ locale: 'en-US' })
    const page = await context.newPage()
    await stubServices(page)
    await page.goto('/login')

    await expect(page.getByRole('button', { name: 'Ingresar' })).toBeVisible()

    await context.close()
  })

  test('switches to English and back, and the choice survives a reload', async ({ page }) => {
    await stubServices(page)
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    const sidebar = page.locator('#tms-sidebar')
    await expect(sidebar.getByRole('link', { name: 'Orígenes', exact: true })).toBeVisible()

    await page.getByRole('button', { name: 'EN', exact: true }).click()
    await expect(sidebar.getByRole('link', { name: 'Origins', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: /^Hello,/ })).toBeVisible()

    await page.reload()
    await expect(sidebar.getByRole('link', { name: 'Origins', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'EN', exact: true })).toHaveAttribute('aria-pressed', 'true')

    await page.getByRole('button', { name: 'ES', exact: true }).click()
    await expect(sidebar.getByRole('link', { name: 'Orígenes', exact: true })).toBeVisible()
    await page.reload()
    await expect(sidebar.getByRole('link', { name: 'Orígenes', exact: true })).toBeVisible()
  })

  test('translates a screen title and its primary action', async ({ page }) => {
    await stubServices(page)
    await signIn(page)
    await page.goto('/masters/origins')

    await expect(page.getByRole('heading', { level: 1, name: 'Orígenes' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Nuevo origen' })).toBeVisible()

    await page.getByRole('button', { name: 'EN', exact: true }).click()

    await expect(page.getByRole('heading', { level: 1, name: 'Origins' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'New origin' })).toBeVisible()
  })

  test('URLs are never translated', async ({ page }) => {
    await stubServices(page)
    await signIn(page)
    await page.goto('/masters/origins')
    await page.getByRole('button', { name: 'EN', exact: true }).click()

    await expect(page.getByRole('heading', { level: 1, name: 'Origins' })).toBeVisible()
    await expect(page).toHaveURL(/\/masters\/origins$/)
  })
})
