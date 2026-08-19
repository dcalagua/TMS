import { expect, test, type Page } from '@playwright/test'
import { signIn, stubServices } from './support/app'

/** Every sidebar entry, with the URL it must reach and the `h1` its screen renders. */
const ENTRIES = [
  { link: 'Inicio', path: '/', heading: /^Hola,/ },
  { link: 'Orígenes', path: '/masters/origins', heading: 'Orígenes' },
  { link: 'Destinos', path: '/masters/destinations', heading: 'Destinos' },
  { link: 'Zonas', path: '/masters/zones', heading: 'Zonas' },
  { link: 'Frecuencias', path: '/masters/frequencies', heading: 'Frecuencias' },
  { link: 'Rutas', path: '/masters/routes', heading: 'Rutas' },
  { link: 'Transportistas', path: '/fleet/carriers', heading: 'Transportistas' },
  { link: 'Tipos de vehículo', path: '/fleet/vehicle-types', heading: 'Tipos de vehículo' },
  { link: 'Vehículos', path: '/fleet/vehicles', heading: 'Vehículos' },
  { link: 'Pedidos', path: '/orders', heading: 'Pedidos' },
  { link: 'Planificación', path: '/planning', heading: 'Planificación' },
  { link: 'Viajes', path: '/trips', heading: 'Viajes' },
  { link: 'Seguridad', path: '/admin/security', heading: 'Seguridad' },
] as const

/** Scoped to the menu: the dashboard also offers quick-access links with the same names. */
function navLink(page: Page, name: string) {
  return page.locator('#tms-sidebar').getByRole('link', { name, exact: true })
}

test.describe('sidebar navigation on desktop', () => {
  test('every entry navigates and mounts its screen', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await stubServices(page)
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    for (const entry of ENTRIES) {
      await navLink(page, entry.link).click()
      await expect(page, `clicking ${entry.link}`).toHaveURL(new RegExp(`${entry.path.replace(/\//g, '\\/')}$`))
      await expect(page.getByRole('heading', { level: 1, name: entry.heading })).toBeVisible()
    }
  })

  test('keeps browser Back and Forward working', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await stubServices(page)
    await signIn(page)

    await navLink(page, 'Zonas').click()
    await expect(page.getByRole('heading', { level: 1, name: 'Zonas' })).toBeVisible()
    await navLink(page, 'Pedidos').click()
    await expect(page.getByRole('heading', { level: 1, name: 'Pedidos' })).toBeVisible()

    await page.goBack()
    await expect(page.getByRole('heading', { level: 1, name: 'Zonas' })).toBeVisible()
    await page.goForward()
    await expect(page.getByRole('heading', { level: 1, name: 'Pedidos' })).toBeVisible()
  })

  test('marks the current entry with aria-current', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await stubServices(page)
    await signIn(page)

    await navLink(page, 'Vehículos').click()

    await expect(navLink(page, 'Vehículos')).toHaveAttribute('aria-current', 'page')
  })

  test('navigates from the keyboard', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await stubServices(page)
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    await navLink(page, 'Transportistas').focus()
    await page.keyboard.press('Enter')

    await expect(page.getByRole('heading', { level: 1, name: 'Transportistas' })).toBeVisible()
  })
})

test.describe('sidebar navigation on mobile', () => {
  test('every entry navigates and closes the drawer', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await stubServices(page)
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    const sidebar = page.locator('#tms-sidebar')
    const toggle = page.getByRole('button', { name: 'Abrir o cerrar la navegación' })

    for (const entry of ENTRIES) {
      await toggle.click()
      await expect(sidebar).toBeVisible()

      await navLink(page, entry.link).click()

      await expect(page, `clicking ${entry.link}`).toHaveURL(new RegExp(`${entry.path.replace(/\//g, '\\/')}$`))
      await expect(page.getByRole('heading', { level: 1, name: entry.heading })).toBeVisible()
      // One tap must both navigate and dismiss the drawer.
      await expect(sidebar).toBeHidden()
    }
  })

  test('closes the drawer with Escape without navigating', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await stubServices(page)
    await signIn(page)
    await page.goto('/orders')
    await expect(page.getByRole('heading', { level: 1, name: 'Pedidos' })).toBeVisible()

    await page.getByRole('button', { name: 'Abrir o cerrar la navegación' }).click()
    await expect(page.locator('#tms-sidebar')).toBeVisible()

    await page.keyboard.press('Escape')

    await expect(page.locator('#tms-sidebar')).toBeHidden()
    await expect(page).toHaveURL(/\/orders$/)
  })
})

test('an unknown URL renders the not-found screen, not a blank page', async ({ page }) => {
  await stubServices(page)
  await signIn(page)
  await page.goto('/this-route-does-not-exist')

  await expect(page.getByText('Esta pantalla no existe.')).toBeVisible()
})
