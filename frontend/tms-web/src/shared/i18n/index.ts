import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import {
  DEFAULT_LANGUAGE,
  FALLBACK_LANGUAGE,
  readStoredLanguage,
  storeLanguage,
  type Language,
} from './config'

import enAuth from './locales/en/auth.json'
import enCommon from './locales/en/common.json'
import enDashboard from './locales/en/dashboard.json'
import enDialogs from './locales/en/dialogs.json'
import enErrors from './locales/en/errors.json'
import enFleet from './locales/en/fleet.json'
import enMaps from './locales/en/maps.json'
import enMasters from './locales/en/masters.json'
import enNavigation from './locales/en/navigation.json'
import enOrders from './locales/en/orders.json'
import enPlanning from './locales/en/planning.json'
import enSecurity from './locales/en/security.json'
import enStatuses from './locales/en/statuses.json'
import enTrips from './locales/en/trips.json'
import enValidations from './locales/en/validations.json'

import esAuth from './locales/es/auth.json'
import esCommon from './locales/es/common.json'
import esDashboard from './locales/es/dashboard.json'
import esDialogs from './locales/es/dialogs.json'
import esErrors from './locales/es/errors.json'
import esFleet from './locales/es/fleet.json'
import esMaps from './locales/es/maps.json'
import esMasters from './locales/es/masters.json'
import esNavigation from './locales/es/navigation.json'
import esOrders from './locales/es/orders.json'
import esPlanning from './locales/es/planning.json'
import esSecurity from './locales/es/security.json'
import esStatuses from './locales/es/statuses.json'
import esTrips from './locales/es/trips.json'
import esValidations from './locales/es/validations.json'

/**
 * Resources are split per domain rather than kept in one file per language: a single bundle
 * becomes unreviewable long before the product is finished, and a namespace tells a reader
 * which screens a key belongs to.
 */
export const resources = {
  es: {
    auth: esAuth,
    common: esCommon,
    dashboard: esDashboard,
    dialogs: esDialogs,
    errors: esErrors,
    fleet: esFleet,
    maps: esMaps,
    masters: esMasters,
    navigation: esNavigation,
    orders: esOrders,
    planning: esPlanning,
    security: esSecurity,
    statuses: esStatuses,
    trips: esTrips,
    validations: esValidations,
  },
  en: {
    auth: enAuth,
    common: enCommon,
    dashboard: enDashboard,
    dialogs: enDialogs,
    errors: enErrors,
    fleet: enFleet,
    maps: enMaps,
    masters: enMasters,
    navigation: enNavigation,
    orders: enOrders,
    planning: enPlanning,
    security: enSecurity,
    statuses: enStatuses,
    trips: enTrips,
    validations: enValidations,
  },
} as const

export const defaultNS = 'common'

export const NAMESPACES = Object.keys(resources.es) as (keyof (typeof resources)['es'])[]

if (!i18n.isInitialized) {
  void i18n.use(initReactI18next).init({
    resources,
    lng: readStoredLanguage() ?? DEFAULT_LANGUAGE,
    fallbackLng: FALLBACK_LANGUAGE,
    defaultNS,
    ns: NAMESPACES,
    interpolation: {
      // React escapes for us; letting i18next escape as well double-encodes accented text.
      escapeValue: false,
    },
  })
}

/** Switches the UI language and remembers the choice for the next visit. */
export async function changeLanguage(language: Language): Promise<void> {
  storeLanguage(language)
  await i18n.changeLanguage(language)
}

export { i18n }
export default i18n
