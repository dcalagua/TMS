import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { localeFor } from './config'

/**
 * Regional formatting, built on `Intl` so the browser owns the rules rather than the codebase
 * carrying its own separators and date patterns. Every helper takes the language explicitly;
 * screens normally use {@link useFormat}, which binds the active one.
 *
 * Only *presentation* goes through here. Values sent to the backend keep their canonical
 * shape - an ISO date stays an ISO date, a decimal stays a dot-decimal number.
 */

const EMPTY = '-'

function numberFormat(language: string, options: Intl.NumberFormatOptions): Intl.NumberFormat {
  return new Intl.NumberFormat(localeFor(language), options)
}

export function formatDate(value: string | Date | null | undefined, language: string): string {
  if (!value) {
    return EMPTY
  }
  const date = value instanceof Date ? value : parseDate(value)
  if (!date) {
    return EMPTY
  }
  return new Intl.DateTimeFormat(localeFor(language), { dateStyle: 'medium' }).format(date)
}

export function formatDateTime(value: string | Date | null | undefined, language: string): string {
  if (!value) {
    return EMPTY
  }
  const date = value instanceof Date ? value : parseDate(value)
  if (!date) {
    return EMPTY
  }
  return new Intl.DateTimeFormat(localeFor(language), { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

/**
 * The clock time alone, for a screen whose date is already stated once at the top - a control
 * tower row saying "14:00" rather than "21 ago 2026, 14:00" eleven times down a column.
 *
 * Rendered in the *browser's* zone, like every other instant the product prints. That is right
 * for an operator sitting in the depot's own time zone and is worth knowing when they are not:
 * the value on the wire carries its offset, and the backend has already done the one conversion
 * that decides a business fact (which day a service window belongs to).
 */
export function formatTime(value: string | Date | null | undefined, language: string): string {
  if (!value) {
    return EMPTY
  }
  const date = value instanceof Date ? value : parseDate(value)
  if (!date) {
    return EMPTY
  }
  return new Intl.DateTimeFormat(localeFor(language), { timeStyle: 'short' }).format(date)
}

/** Accepts both an ISO instant and a bare `yyyy-mm-dd`, which the backend uses for plan dates.
 * A bare date is read as local time so it cannot shift a day across time zones. */
function parseDate(value: string): Date | null {
  const bareDate = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  const date = bareDate
    ? new Date(Number(bareDate[1]), Number(bareDate[2]) - 1, Number(bareDate[3]))
    : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatNumber(
  value: number | null | undefined,
  language: string,
  options: Intl.NumberFormatOptions = {},
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY
  }
  return numberFormat(language, options).format(value)
}

/** A plain count: pallets, orders, stops. No decimals. */
export function formatQuantity(value: number | null | undefined, language: string): string {
  return formatNumber(value, language, { maximumFractionDigits: 0 })
}

export function formatDecimal(value: number | null | undefined, language: string, fractionDigits = 2): string {
  return formatNumber(value, language, {
    minimumFractionDigits: 0,
    maximumFractionDigits: fractionDigits,
  })
}

export function formatWeightKg(value: number | null | undefined, language: string): string {
  return formatNumber(value, language, {
    style: 'unit',
    unit: 'kilogram',
    unitDisplay: 'short',
    maximumFractionDigits: 2,
  })
}

/** `Intl` has no sanctioned `cubic-meter` unit (ECMA-402 lists only a fixed set), so the
 * number is localised and the SI symbol - identical in every language TMS ships - appended. */
export function formatVolumeM3(value: number | null | undefined, language: string): string {
  const amount = formatNumber(value, language, { maximumFractionDigits: 2 })
  return amount === EMPTY ? EMPTY : `${amount} m³`
}

/** `value` is a percentage already expressed 0-100, as the backend reports capacity usage. */
export function formatPercent(value: number | null | undefined, language: string, fractionDigits = 0): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY
  }
  return numberFormat(language, {
    style: 'percent',
    maximumFractionDigits: fractionDigits,
  }).format(value / 100)
}

export interface Formatters {
  date: (value: string | Date | null | undefined) => string
  dateTime: (value: string | Date | null | undefined) => string
  time: (value: string | Date | null | undefined) => string
  number: (value: number | null | undefined, options?: Intl.NumberFormatOptions) => string
  quantity: (value: number | null | undefined) => string
  decimal: (value: number | null | undefined, fractionDigits?: number) => string
  weight: (value: number | null | undefined) => string
  volume: (value: number | null | undefined) => string
  percent: (value: number | null | undefined, fractionDigits?: number) => string
}

/** The formatters bound to the active UI language; re-created only when the language changes. */
export function useFormat(): Formatters {
  const { i18n } = useTranslation()
  const language = i18n.language

  return useMemo<Formatters>(
    () => ({
      date: (value) => formatDate(value, language),
      dateTime: (value) => formatDateTime(value, language),
      time: (value) => formatTime(value, language),
      number: (value, options) => formatNumber(value, language, options),
      quantity: (value) => formatQuantity(value, language),
      decimal: (value, fractionDigits) => formatDecimal(value, language, fractionDigits),
      weight: (value) => formatWeightKg(value, language),
      volume: (value) => formatVolumeM3(value, language),
      percent: (value, fractionDigits) => formatPercent(value, language, fractionDigits),
    }),
    [language],
  )
}
