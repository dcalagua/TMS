import { expect, test, type Page } from '@playwright/test'
import { signIn, stubServices } from './support/app'
import { stubOrigins } from './support/masters'

/**
 * The monochrome rule, measured rather than asserted.
 *
 * Remapping Bootstrap's `--bs-*` properties does not reach the literal hexes it compiles into
 * `.btn-outline-primary`, `.form-check-input:checked`, `.pagination` and the focus rules, so
 * "we removed the blue" is a claim that has to be checked against what the browser actually
 * paints. This walks the real screens and reads every computed colour.
 *
 * A colour counts as blue residue when its blue channel dominates both others by a wide margin.
 * Bootstrap's #0d6efd clears that by 143; the product's own slate `--tms-info` (#3a4552) sits at
 * 13, and the ink focus ring at 5, so the threshold separates framework blue from a neutral
 * that merely leans cool.
 */

const BLUE_DOMINANCE_LIMIT = 40

interface Offender {
  selector: string
  property: string
  colour: string
}

async function findBlues(page: Page): Promise<Offender[]> {
  return page.evaluate((limit) => {
    const found: { selector: string; property: string; colour: string }[] = []

    function parse(value: string): [number, number, number] | null {
      const match = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(value)
      if (!match) return null
      return [Number(match[1]), Number(match[2]), Number(match[3])]
    }

    function describe(element: Element): string {
      const tag = element.tagName.toLowerCase()
      const cls = typeof element.className === 'string' ? element.className.split(/\s+/).slice(0, 2).join('.') : ''
      return cls ? `${tag}.${cls}` : tag
    }

    const properties = ['color', 'backgroundColor', 'borderTopColor', 'borderLeftColor', 'outlineColor']

    for (const element of Array.from(document.querySelectorAll('*'))) {
      const styles = getComputedStyle(element)
      for (const property of properties) {
        const raw = styles[property as keyof CSSStyleDeclaration] as string
        const rgb = typeof raw === 'string' ? parse(raw) : null
        if (!rgb) continue
        const [r, g, b] = rgb
        if (b - Math.max(r, g) > limit) {
          found.push({ selector: describe(element), property, colour: raw })
        }
      }
      // Shadows carry the focus ring, which is where the accent used to live.
      const shadow = styles.boxShadow
      const shadowRgb = parse(shadow)
      if (shadowRgb && shadowRgb[2] - Math.max(shadowRgb[0], shadowRgb[1]) > limit) {
        found.push({ selector: describe(element), property: 'boxShadow', colour: shadow })
      }
    }

    return found
  }, BLUE_DOMINANCE_LIMIT)
}

test('no framework blue survives on the list screens, their menus or their drawers', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await stubServices(page)
  await stubOrigins(page)
  await signIn(page)

  await page.goto('/masters/origins')
  await expect(page.getByText('LIM-CD1')).toBeVisible()
  expect(await findBlues(page), 'list screen').toEqual([])

  // A focused field is the state an operator sees most often, and it is where the accent used
  // to be blue.
  await page.getByLabel(/^Código/).first().focus()
  expect(await findBlues(page), 'focused filter field').toEqual([])

  await page.getByRole('button', { name: 'Abrir menú de acciones' }).first().click()
  await expect(page.getByRole('menu')).toBeVisible()
  expect(await findBlues(page), 'row action menu').toEqual([])
  await page.keyboard.press('Escape')

  await page.getByRole('button', { name: 'Nuevo origen' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByLabel(/^Código/).last().focus()
  expect(await findBlues(page), 'drawer with a focused field').toEqual([])
})

test('no framework blue survives in the top bar controls or the sign-in screen', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await stubServices(page)

  await page.goto('/login')
  await expect(page.getByRole('button', { name: 'Ingresar' })).toBeVisible()
  expect(await findBlues(page), 'sign-in screen').toEqual([])

  await signIn(page)
  await expect(page.getByRole('heading', { level: 1, name: /^Hola,/ })).toBeVisible()

  await page.getByRole('button', { name: /EBIM Logistics Peru/ }).click()
  expect(await findBlues(page), 'company switcher open').toEqual([])
})
