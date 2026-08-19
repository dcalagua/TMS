import { useTranslation } from 'react-i18next'
import { changeLanguage } from '../i18n'
import { SUPPORTED_LANGUAGES, isLanguage, type Language } from '../i18n/config'

/**
 * ES | EN switch for the top bar. A segmented pair of buttons rather than a dropdown: two
 * options are faster to reach in one tap, and the current language stays visible instead of
 * being hidden behind a menu.
 */
export function LanguageSwitcher() {
  const { t, i18n } = useTranslation('common')
  const active = isLanguage(i18n.language.split('-')[0] ?? '') ? (i18n.language.split('-')[0] as Language) : 'es'

  return (
    <div className="btn-group btn-group-sm" role="group" aria-label={t('language.label')}>
      {SUPPORTED_LANGUAGES.map((language) => {
        const selected = language === active
        return (
          <button
            key={language}
            type="button"
            className={`btn ${selected ? 'btn-light' : 'btn-outline-light'}`}
            aria-pressed={selected}
            onClick={() => void changeLanguage(language)}
          >
            {t(`language.${language}`)}
          </button>
        )
      })}
    </div>
  )
}
