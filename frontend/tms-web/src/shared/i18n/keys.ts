import type { ParseKeys } from 'i18next'

/**
 * Translation keys as types, so configuration that carries a key instead of display text
 * (the navigation tree, the placeholder screens) is still checked at build time. A renamed or
 * deleted key then breaks `npm run typecheck` rather than rendering `items.origins` to an
 * operator.
 */
export type NavigationKey = ParseKeys<'navigation'>
export type CommonKey = ParseKeys<'common'>
export type StatusKey = ParseKeys<'statuses'>
