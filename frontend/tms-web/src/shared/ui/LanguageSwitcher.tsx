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
 *
 * Styled by the design system rather than by `.btn-group` with Bootstrap's grey `secondary`
 * buttons: in the top bar those sat at a different height and weight from the company and
 * account controls beside them, which is what made the right-hand cluster look assembled out
 * of spare parts.
 */
export function LanguageSwitcher({ variant = 'default' }: LanguageSwitcherProps) {
  const { t, i18n } = useTranslation('common')
  const base = i18n.language.split('-')[0] ?? ''
  const active: Language = isLanguage(base) ? base : 'es'

  return (
    <div
      className={`tms-segmented${variant === 'inverse' ? ' tms-segmented-inverse' : ''}`}
      role="group"
      aria-label={t('language.label')}
    >
      {SUPPORTED_LANGUAGES.map((language) => {
        const selected = language === active
        return (
          <button
            key={language}
            type="button"
            className={`tms-segmented-option${selected ? ' is-selected' : ''}`}
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
