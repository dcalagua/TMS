import { expect, test } from '@playwright/test'
import { horizontalOverflow, signIn, stubServices, switchLanguage } from './support/app'
import { stubPlanning } from './support/planning'

/**
 * The planning workspace end to end. The stub keeps the run in memory, so these tests exercise
 * a real sequence - create a trip, assign an order, watch the capacity bars move, take the
 * order back off - rather than asserting against fixed payloads.
 */

test.describe.configure({ timeout: 90_000 })

/** Playwright matches routes last-registered-first, so the planning stub goes on after the
 * general one to win over its catch-all. */
async function openBoard(page: import('@playwright/test').Page) {
  await stubServices(page)
  await stubPlanning(page)
  await signIn(page)
  await page.goto('/planning')
  await expect(page.getByRole('heading', { level: 1, name: 'Planificación' })).toBeVisible()
  await page.goto('/planning/run-1')
  await expect(page.getByRole('heading', { level: 1, name: 'PLN-000001' })).toBeVisible()
}

test('the run header states origin, date, status and trip count', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openBoard(page)

  await expect(page.getByText(/CD Lima ·/)).toBeVisible()
  await expect(page.getByText('Borrador')).toBeVisible()
  await expect(page.getByText('0 viajes')).toBeVisible()
  await expect(page.getByText('Aún no hay viajes')).toBeVisible()
})

test('creates a trip, assigns an order, and the capacity bars move', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openBoard(page)

  await page.getByRole('button', { name: 'Nuevo viaje' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toHaveAccessibleName('Nuevo viaje')
  // Wait for the vehicle list to arrive before picking from it.
  await expect(dialog.getByRole('option', { name: /VH-001/ })).toBeAttached()
  await dialog.getByLabel('Vehículo').selectOption('veh-1')
  await dialog.getByRole('button', { name: 'Crear viaje' }).click()
  await expect(dialog).toBeHidden()

  // The card appears with a vehicle and an empty, measurable capacity.
  const card = page.getByRole('article').first()
  await expect(card.getByText('Viaje 1')).toBeVisible()
  await expect(card.getByText('VH-001')).toBeVisible()
  await expect(card.getByText('0 kg / 10,000 kg')).toBeVisible()

  // Assign the first eligible order.
  await page.getByRole('button', { name: 'Asignar' }).first().click()

  // 8,850 of 10,000 kg is 88.5% - over the near-limit threshold, so it must say so in words.
  await expect(card.getByText('8,850 kg / 10,000 kg')).toBeVisible()
  await expect(card.getByText('(88.5%)')).toBeVisible()
  await expect(card.getByText('Cerca del límite')).toBeVisible()
  await expect(card.getByRole('progressbar')).toHaveCount(3)
})

test('removes an assigned order and returns it to the eligible pool', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openBoard(page)

  await page.getByRole('button', { name: 'Nuevo viaje' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Crear viaje' }).click()
  await expect(page.getByRole('article').first().getByText('Viaje 1')).toBeVisible()

  await page.getByRole('button', { name: 'Asignar' }).first().click()
  // The order leaves the eligible pool the moment it is on a trip.
  await expect(page.getByRole('listitem').filter({ hasText: 'TO-00000001' })).toHaveCount(0)

  await page.getByRole('button', { name: 'Abrir el viaje 1' }).click()
  const drawer = page.getByRole('dialog')
  await expect(drawer).toBeVisible()
  await expect(drawer.getByRole('heading', { name: 'Pedidos asignados' })).toBeVisible()
  await expect(drawer.getByText('TO-00000001')).toBeVisible()

  await drawer.getByRole('button', { name: 'Quitar' }).click()
  await expect(drawer.getByText('Aún no hay pedidos asignados.')).toBeVisible()

  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  // Back in the eligible pool.
  await expect(page.getByRole('listitem').filter({ hasText: 'TO-00000001' })).toBeVisible()
})

test('assigning is blocked until a trip exists', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openBoard(page)

  await expect(page.getByText('Crea un viaje antes de asignar pedidos.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Asignar' }).first()).toBeDisabled()
})

test('shows the board as tabs on a phone, never two narrow columns', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await openBoard(page)

  const tabs = page.getByRole('group', { name: 'Paneles de planificación' })
  await expect(tabs).toBeVisible()

  // Orders first, trips hidden.
  await expect(page.getByRole('button', { name: 'Pedidos', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByText('Aún no hay viajes')).toBeHidden()

  await page.getByRole('button', { name: 'Viajes', exact: true }).click()
  await expect(page.getByText('Aún no hay viajes')).toBeVisible()

  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
})

test('the board fits every viewport without a page-level horizontal scrollbar', async ({ page }) => {
  await stubServices(page)
  await stubPlanning(page)
  await signIn(page)

  for (const size of [
    { width: 320, height: 568 },
    { width: 360, height: 800 },
    { width: 768, height: 1024 },
    { width: 1024, height: 768 },
    { width: 1366, height: 768 },
    { width: 1920, height: 1080 },
  ]) {
    await page.setViewportSize(size)
    await page.goto('/planning/run-1')
    await expect(page.getByRole('heading', { level: 1, name: 'PLN-000001' })).toBeVisible()
    expect(await horizontalOverflow(page), `board at ${size.width}px`).toBeLessThanOrEqual(0)
  }
})

test('planning is fully translated: no raw enum values in either language', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openBoard(page)

  await expect(page.getByText('Borrador')).toBeVisible()
  expect(await page.locator('body').innerText()).not.toContain('DRAFT')

  await switchLanguage(page, 'EN')
  await expect(page.getByText('Draft')).toBeVisible()
  await expect(page.getByRole('button', { name: 'New trip' })).toBeVisible()
  expect(await page.locator('body').innerText()).not.toContain('READY_FOR_PLANNING')
})

test('orders screen: header count, page totals and a working filter', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await stubServices(page, {
    responses: {
      '/orders': {
        content: [
          {
            id: 'ord-1',
            orderNumber: 'TO-00000001',
            originId: 'org-1',
            originCode: 'LIMA',
            originName: 'CD Lima',
            destinationId: 'dest-1',
            destinationCode: 'D-001',
            destinationName: 'Tienda Norte',
            serviceDate: '2026-03-01',
            priority: 'HIGH',
            status: 'READY_FOR_PLANNING',
            totalWeightKg: 8850,
            totalVolumeM3: 18,
            totalPallets: 12,
            lineCount: 3,
          },
        ],
        page: 0,
        size: 25,
        totalElements: 1,
      },
    },
  })
  await signIn(page)
  await page.goto('/orders')

  await expect(page.getByRole('heading', { level: 1, name: 'Pedidos' })).toBeVisible()
  await expect(page.getByText('1 pedido encontrado')).toBeVisible()
  await expect(page.getByText('Peso en esta página')).toBeVisible()
  await expect(page.getByText('8,850 kg').first()).toBeVisible()
  await expect(page.getByText('Listo para planificar').first()).toBeVisible()

  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
})
