import 'i18next'
import type { defaultNS, resources } from './index'

/**
 * Makes `t()` key-checked at compile time against the Spanish bundle, which is the product's
 * source of truth. A typo in a key, or a key deleted from the resources but still referenced
 * by a screen, then fails `npm run typecheck` instead of rendering the raw key to an operator.
 */
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: typeof defaultNS
    resources: (typeof resources)['es']
  }
}
