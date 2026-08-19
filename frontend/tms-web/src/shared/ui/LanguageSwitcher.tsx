import { useTranslation } from 'react-i18next'
import { changeLanguage } from '../i18n'
import { SUPPORTED_LANGUAGES, isLanguage, type Language } from '../i18n/config'

export interface LanguageSwitcherProps {
  /** Renders for a dark background, as on the sign-in screen's brand panel. */
  variant?: 'default' | 'inverse'
}

/**
 * ES | EN switch. A segmented pair of buttons rather than a dropdown: two options are faster
 * to reach in one tap, and the current language stays visible instead of being hidden behind
 * a menu. `aria-pressed` is what conveys the selection, not the fill colour alone.
 */
export function LanguageSwitcher({ variant = 'default' }: LanguageSwitcherProps) {
  const { t, i18n } = useTranslation('common')
  const base = i18n.language.split('-')[0] ?? ''
  const active: Language = isLanguage(base) ? base : 'es'

  const selectedClass = variant === 'inverse' ? 'btn-light' : 'btn-secondary'
  const unselectedClass = variant === 'inverse' ? 'btn-outline-light' : 'btn-outline-secondary'

  return (
    <div className="btn-group btn-group-sm" role="group" aria-label={t('language.label')}>
      {SUPPORTED_LANGUAGES.map((language) => {
        const selected = language === active
        return (
          <button
            key={language}
            type="button"
            className={`btn ${selected ? selectedClass : unselectedClass}`}
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
