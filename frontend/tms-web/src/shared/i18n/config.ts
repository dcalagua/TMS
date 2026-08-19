/**
 * Language policy for TMS by EBIM.
 *
 * Spanish is the product's language and the first thing a new visitor sees; English is only a
 * fallback for keys a translation is missing. The browser's `Accept-Language` is deliberately
 * not consulted for the initial choice - an operator on a machine configured in English must
 * still land on the Spanish UI the rest of the team uses.
 */
export const SUPPORTED_LANGUAGES = ['es', 'en'] as const

export type Language = (typeof SUPPORTED_LANGUAGES)[number]

export const DEFAULT_LANGUAGE: Language = 'es'
export const FALLBACK_LANGUAGE: Language = 'en'

export const LANGUAGE_STORAGE_KEY = 'tms.language'

/**
 * The BCP 47 locale each language formats with. Peru is the product's current market, so `es`
 * formats as `es-PE` (dot thousands separator, `dd/mm/yyyy`, 24-hour clock) rather than the
 * generic `es`; `en-US` is the neutral counterpart for the English UI.
 */
export const LOCALES: Record<Language, string> = {
  es: 'es-PE',
  en: 'en-US',
}

export function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (SUPPORTED_LANGUAGES as readonly string[]).includes(value)
}

/** The stored preference, or `null` when there is none or storage is unavailable. */
export function readStoredLanguage(): Language | null {
  try {
    const stored = window.localStorage.getItem(LANGUAGE_STORAGE_KEY)
    return isLanguage(stored) ? stored : null
  } catch {
    return null
  }
}

export function storeLanguage(language: Language): void {
  try {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, language)
  } catch {
    // Private mode or disabled storage: the switch still applies to this tab, it just does
    // not survive a reload.
  }
}

/** Maps a language (or a full i18next language tag) to the locale used for `Intl` formatting. */
export function localeFor(language: string): string {
  const base = language.split('-')[0]
  return isLanguage(base) ? LOCALES[base] : LOCALES[DEFAULT_LANGUAGE]
}
