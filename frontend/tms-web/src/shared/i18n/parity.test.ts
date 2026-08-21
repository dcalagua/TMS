import { describe, expect, it } from 'vitest'
import { NAMESPACES, resources } from './index'

/**
 * Spanish and English must carry exactly the same keys.
 *
 * A missing key does not fail loudly. i18next falls back to the other language, or renders the
 * key path itself - so `orders.detail.fulfillment` appears on a customer's screen and nothing in
 * the build says a word. That is the failure this file exists to convert into a red test, and it
 * is not hypothetical: the Sellable V4 pack shipped the entire drivers feature with no keys in
 * either language, and the only reason it was caught is that the key *type* is generated from the
 * Spanish bundle, so TypeScript refused to compile. English has no such backstop.
 *
 * Compared by key path and not by count. Two bundles that are each missing one of the other's
 * keys have identical counts and are both wrong, which is exactly the case a count would pass.
 */

type Bundle = Record<string, unknown>

/** Every leaf key path in a bundle, dotted. Objects are walked; anything else is a leaf. */
function keyPaths(bundle: Bundle, prefix = ''): string[] {
  return Object.entries(bundle).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? keyPaths(value as Bundle, path)
      : [path]
  })
}

describe('locale parity', () => {
  it('ships the same namespaces in both languages', () => {
    expect(Object.keys(resources.en).sort()).toEqual(Object.keys(resources.es).sort())
  })

  it.each(NAMESPACES)('has the same keys in es and en for %s', (namespace) => {
    const es = keyPaths(resources.es[namespace] as Bundle)
    const en = keyPaths(resources.en[namespace] as Bundle)

    // Reported as two directed differences rather than one symmetric one, because the fix
    // differs: a key only in Spanish needs translating, a key only in English is usually a
    // rename that was applied to one bundle.
    expect(es.filter((key) => !en.includes(key)), `${namespace}: missing from en`).toEqual([])
    expect(en.filter((key) => !es.includes(key)), `${namespace}: missing from es`).toEqual([])
  })

  it.each(NAMESPACES)('has no empty translation in %s', (namespace) => {
    for (const language of ['es', 'en'] as const) {
      const bundle = resources[language][namespace] as Bundle
      const blank = keyPaths(bundle).filter((path) => {
        const value = path
          .split('.')
          .reduce<unknown>((node, key) => (node as Bundle)?.[key], bundle)
        return typeof value === 'string' && value.trim() === ''
      })
      expect(blank, `${namespace}: blank in ${language}`).toEqual([])
    }
  })

  it('counts the same number of keys overall', () => {
    // Not the assertion that catches drift - the per-namespace comparisons above do that - but
    // it is the number a report quotes, and pinning it here means the report cannot quote a
    // figure nobody checked.
    const total = (language: 'es' | 'en') =>
      NAMESPACES.reduce((sum, ns) => sum + keyPaths(resources[language][ns] as Bundle).length, 0)

    expect(total('en')).toBe(total('es'))
  })
})
