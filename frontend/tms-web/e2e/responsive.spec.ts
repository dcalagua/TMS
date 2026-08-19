import { expect, test } from '@playwright/test'
import { horizontalOverflow, signIn, stubServices } from './support/app'

/**
 * The layout contract, checked in a real browser at every viewport the brief lists.
 *
 * `document.documentElement.scrollWidth <= window.innerWidth` is the whole rule: a wide table
 * or a long company name must scroll inside its own container, never widen the page. jsdom
 * cannot check this - it has no layout - so it belongs here rather than in the unit suite.
 */

const VIEWPORTS = [
  { name: '320x568', width: 320, height: 568 },
  { name: '360x800', width: 360, height: 800 },
  { name: '390x844', width: 390, height: 844 },
  { name: '768x1024', width: 768, height: 1024 },
  { name: '1024x768', width: 1024, height: 768 },
  { name: '1366x768', width: 1366, height: 768 },
  { name: '1440x900', width: 1440, height: 900 },
  { name: '1920x1080', width: 1920, height: 1080 },
] as const

const SCREENS = [
  { path: '/', heading: /^Hola,/ },
  { path: '/masters/origins', heading: 'Orígenes' },
  { path: '/masters/destinations', heading: 'Destinos' },
  { path: '/fleet/vehicles', heading: 'Vehículos' },
  { path: '/orders', heading: 'Pedidos' },
  { path: '/planning', heading: 'Planificación' },
] as const

test.describe('no global horizontal overflow', () => {
  for (const viewport of VIEWPORTS) {
    test(`sign-in screen fits ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await stubServices(page)
      await page.goto('/login')
      await expect(page.getByRole('button', { name: 'Ingresar' })).toBeVisible()

      expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
    })
  }

  for (const viewport of VIEWPORTS) {
    test(`application shell fits ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await stubServices(page)
      await signIn(page)
      await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

      for (const screen of SCREENS) {
        await page.goto(screen.path)
        await expect(page.getByRole('heading', { level: 1, name: screen.heading })).toBeVisible()
        expect(await horizontalOverflow(page), `${screen.path} at ${viewport.name}`).toBeLessThanOrEqual(0)
      }
    })
  }
})

test.describe('shell behaviour across breakpoints', () => {
  test('shows a static sidebar on desktop and a drawer on mobile', async ({ page }) => {
    await stubServices(page)

    await page.setViewportSize({ width: 1440, height: 900 })
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    const sidebar = page.locator('#tms-sidebar')
    await expect(sidebar).toBeVisible()
    await expect(page.getByRole('button', { name: 'Abrir o cerrar la navegación' })).toBeHidden()

    await page.setViewportSize({ width: 390, height: 844 })
    await expect(sidebar).toBeHidden()
    await expect(page.getByRole('button', { name: 'Abrir o cerrar la navegación' })).toBeVisible()
  })

  test('collapses the desktop sidebar to a rail and remembers the choice', async ({ page }) => {
    await stubServices(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await signIn(page)
    await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

    const sidebar = page.locator('#tms-sidebar')
    const expanded = (await sidebar.boundingBox())?.width ?? 0
    expect(expanded).toBeGreaterThan(200)

    await page.getByRole('button', { name: 'Contraer el menú' }).click()
    await expect(sidebar).toHaveClass(/tms-sidebar-rail/)
    // The width is animated, so measuring the moment the class lands reads the old width.
    await sidebar.evaluate(async (element) => {
      await Promise.all(element.getAnimations().map((animation) => animation.finished))
    })
    const railed = (await sidebar.boundingBox())?.width ?? 0
    expect(railed).toBeLessThan(expanded)

    // The preference must survive a reload, which is the point of persisting it.
    await page.reload()
    await expect(page.locator('#tms-sidebar')).toHaveClass(/tms-sidebar-rail/)
    await expect(page.getByRole('button', { name: 'Expandir el menú' })).toBeVisible()
  })

  test('keeps a wide table inside its own scroll container', async ({ page }) => {
    await stubServices(page, {
      responses: {
        '/vehicles': {
          content: Array.from({ length: 12 }, (_unused, index) => ({
            id: `veh-${index}`,
            code: `VEH-${String(index).padStart(4, '0')}`,
            plate: `ABC-${100 + index}`,
            carrierName: 'Transportes de Muy Largo Nombre Sociedad Anonima Cerrada',
            vehicleTypeName: 'Camion de tres ejes con furgon refrigerado',
            weightCapacityKg: 12000,
            volumeCapacityM3: 45.5,
            palletCapacity: 22,
            availabilityStatus: 'AVAILABLE',
            active: true,
          })),
          page: 0,
          size: 25,
          totalElements: 12,
        },
      },
    })

    await page.setViewportSize({ width: 768, height: 1024 })
    await signIn(page)
    await page.goto('/fleet/vehicles')
    await expect(page.getByRole('heading', { level: 1, name: 'Vehículos' })).toBeVisible()

    expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
  })
})
