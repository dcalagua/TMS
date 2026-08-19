import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LanguageSwitcher } from '../ui/LanguageSwitcher'
import { Sidebar } from '../ui/Sidebar'
import i18n, { changeLanguage, resources } from './index'
import {
  DEFAULT_LANGUAGE,
  FALLBACK_LANGUAGE,
  LANGUAGE_STORAGE_KEY,
  isLanguage,
  localeFor,
  readStoredLanguage,
  storeLanguage,
} from './config'
import {
  formatDate,
  formatDateTime,
  formatDecimal,
  formatPercent,
  formatQuantity,
  formatVolumeM3,
  formatWeightKg,
} from './format'

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

beforeEach(() => {
  companyMocks.useCompany.mockReturnValue({ status: 'ready', hasCapability: () => true })
  window.localStorage.clear()
})

afterEach(async () => {
  window.localStorage.clear()
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('language policy', () => {
  it('defaults to Spanish and falls back to English', () => {
    expect(DEFAULT_LANGUAGE).toBe('es')
    expect(FALLBACK_LANGUAGE).toBe('en')
  })

  it('ignores the browser language for the first choice: no stored preference means Spanish', () => {
    expect(readStoredLanguage()).toBeNull()
    expect(readStoredLanguage() ?? DEFAULT_LANGUAGE).toBe('es')
  })

  it('only accepts languages the product actually ships', () => {
    expect(isLanguage('es')).toBe(true)
    expect(isLanguage('en')).toBe(true)
    expect(isLanguage('fr')).toBe(false)
    expect(isLanguage(null)).toBe(false)
  })

  it('ignores a stored value that is not a supported language', () => {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, 'klingon')

    expect(readStoredLanguage()).toBeNull()
  })

  it('persists the choice so the next visit starts in it', async () => {
    await changeLanguage('en')

    expect(window.localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('en')
    // A reload reads the same storage the boot sequence reads.
    expect(readStoredLanguage()).toBe('en')
  })

  it('maps each language to its regional locale', () => {
    expect(localeFor('es')).toBe('es-PE')
    expect(localeFor('en')).toBe('en-US')
    expect(localeFor('en-GB')).toBe('en-US')
    expect(localeFor('unknown')).toBe('es-PE')
  })

  it('survives storage being unavailable instead of crashing the app', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage disabled')
    })
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled')
    })

    expect(() => storeLanguage('en')).not.toThrow()
    expect(readStoredLanguage()).toBeNull()

    setItem.mockRestore()
    getItem.mockRestore()
  })
})

function flatKeys(node: unknown, prefix = ''): string[] {
  if (typeof node !== 'object' || node === null) {
    return [prefix]
  }
  return Object.entries(node).flatMap(([key, value]) => flatKeys(value, prefix ? `${prefix}.${key}` : key))
}

describe('resource bundles', () => {
  it('carries exactly the same keys in both languages', () => {
    const spanish = resources.es as Record<string, unknown>
    const english = resources.en as Record<string, unknown>

    expect(Object.keys(english).sort()).toEqual(Object.keys(spanish).sort())

    for (const namespace of Object.keys(spanish)) {
      // A key present in Spanish but missing in English would silently fall back and ship a
      // Spanish sentence inside the English UI; the reverse ships dead weight.
      expect(flatKeys(english[namespace]).sort(), namespace).toEqual(flatKeys(spanish[namespace]).sort())
    }
  })

  it('has no empty translation anywhere', () => {
    for (const [language, bundle] of Object.entries(resources)) {
      for (const [namespace, body] of Object.entries(bundle)) {
        for (const key of flatKeys(body)) {
          const value = i18n.getFixedT(language, namespace as never)(key as never)
          expect(String(value).trim(), `${language}/${namespace}/${key}`).not.toBe('')
        }
      }
    }
  })

  it('translates every known API problem code in both languages', () => {
    const codes = [
      'unauthenticated',
      'invalid-token',
      'principal-not-provisioned',
      'company-scope-required',
      'company-scope-invalid',
      'company-scope-forbidden',
      'access-denied',
      'validation-failed',
      'malformed-request',
      'resource-not-found',
      'conflict',
      'internal-error',
    ]

    for (const language of ['es', 'en'] as const) {
      for (const code of codes) {
        const text = i18n.getFixedT(language, 'errors')(`codes.${code}` as never)
        expect(text, `${language}/${code}`).not.toBe(`codes.${code}`)
        expect(String(text).length).toBeGreaterThan(0)
      }
    }
  })

  it('translates the record statuses rather than showing raw enum values', () => {
    expect(i18n.getFixedT('es', 'statuses')('active')).toBe('Activo')
    expect(i18n.getFixedT('es', 'statuses')('inactive')).toBe('Inactivo')
    expect(i18n.getFixedT('es', 'statuses')('maintenance')).toBe('Mantenimiento')
    expect(i18n.getFixedT('en', 'statuses')('outOfService')).toBe('Out of service')
  })

  it('translates the sign-in screen copy the brief lists', () => {
    const es = i18n.getFixedT('es', 'auth')
    expect(es('login.title')).toBe('Iniciar sesión')
    expect(es('login.email')).toBe('Correo electrónico')
    expect(es('login.password')).toBe('Contraseña')
    expect(es('login.submit')).toBe('Ingresar')
    expect(es('login.submitting')).toBe('Ingresando...')
    expect(es('login.welcome')).toBe('Bienvenido')
    expect(es('login.subtitle')).toBe('Ingresa tus credenciales para continuar')
  })
})

describe('LanguageSwitcher', () => {
  function renderSwitcherWithMenu() {
    return render(
      <MemoryRouter>
        <LanguageSwitcher />
        <Sidebar open={false} collapsed={false} onRequestClose={() => {}} />
      </MemoryRouter>,
    )
  }

  it('starts in Spanish and renders the navigation in Spanish', () => {
    renderSwitcherWithMenu()

    expect(screen.getByRole('button', { name: 'ES' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('link', { name: 'Orígenes' })).toBeInTheDocument()
    expect(screen.getByText('Maestros')).toBeInTheDocument()
  })

  it('switches ES to EN and retranslates the navigation live', async () => {
    const user = userEvent.setup()
    renderSwitcherWithMenu()

    await user.click(screen.getByRole('button', { name: 'EN' }))

    await waitFor(() => expect(screen.getByRole('link', { name: 'Origins' })).toBeInTheDocument())
    expect(screen.getByText('Master data')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Orígenes' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'EN' })).toHaveAttribute('aria-pressed', 'true')
    expect(window.localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('en')
  })

  it('switches EN back to ES', async () => {
    const user = userEvent.setup()
    await changeLanguage('en')
    renderSwitcherWithMenu()

    await user.click(screen.getByRole('button', { name: 'ES' }))

    await waitFor(() => expect(screen.getByRole('link', { name: 'Orígenes' })).toBeInTheDocument())
    expect(window.localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('es')
  })
})

describe('regional formatting', () => {
  it('formats numbers with the separators each locale expects', () => {
    expect(formatQuantity(1234567, 'es')).toBe('1,234,567')
    expect(formatQuantity(1234567, 'en')).toBe('1,234,567')
    expect(formatDecimal(1234.5, 'es')).toBe('1,234.5')
  })

  it('formats weights, volumes and percentages with their units', () => {
    expect(formatWeightKg(1250.5, 'es')).toContain('kg')
    expect(formatVolumeM3(12.25, 'es')).toMatch(/m³/)
    expect(formatPercent(87, 'es')).toBe('87%')
    expect(formatPercent(87.4, 'en', 1)).toBe('87.4%')
  })

  it('formats a bare backend date without shifting it across a time zone', () => {
    // `2026-03-01` must stay 1 March, not become 28 February in a negative-offset zone.
    expect(formatDate('2026-03-01', 'es')).toMatch(/2026/)
    expect(formatDate('2026-03-01', 'es')).toMatch(/3|mar/i)
    expect(formatDate('2026-03-01', 'en')).toMatch(/Mar/)
  })

  it('renders a dash rather than "Invalid Date" or "NaN" for missing values', () => {
    expect(formatDate(null, 'es')).toBe('-')
    expect(formatDateTime(undefined, 'es')).toBe('-')
    expect(formatQuantity(null, 'es')).toBe('-')
    expect(formatPercent(null, 'es')).toBe('-')
    expect(formatDate('not-a-date', 'es')).toBe('-')
  })
})
