import { useTranslation } from 'react-i18next'
import { THEME_NAMES, useTheme, type ThemeName } from '../theme/ThemeProvider'

/**
 * Light/dark toggle for the top bar.
 *
 * A single button rather than a two-option segment: there are exactly two states, the icon
 * shows the one you would move to, and the bar has no room to spend on a control that is used
 * once and then left alone. The accessible name says what the press will do, not what is
 * currently on - "dark mode" on its own leaves a screen-reader user guessing whether it is a
 * state or an action.
 */
export function ModeToggle() {
  const { t } = useTranslation('auth')
  const { mode, toggleMode } = useTheme()
  const goingDark = mode === 'light'
  const label = goingDark ? t('modeToggleToDark') : t('modeToggleToLight')

  return (
    <button type="button" className="tms-icon-btn" onClick={toggleMode} aria-label={label} title={label}>
      <i className={`bi ${goingDark ? 'bi-moon' : 'bi-sun'}`} aria-hidden="true" />
    </button>
  )
}

/* `as const` keeps the literal types, which is what lets `t()` check these against the
   translation keys instead of accepting any string. */
const THEME_LABEL = {
  neutral: 'themeNeutral',
  ebim: 'themeEbim',
} as const satisfies Record<ThemeName, string>

/**
 * Brand picker for the account menu, built as the same segmented control as the language pair
 * so the preferences group reads as one set of choices rather than three widgets.
 *
 * Each option carries a swatch of the theme it selects. A list of names alone makes the user
 * pick blind and then undo it; the swatch is the shortest possible preview.
 */
export function ThemePicker() {
  const { t } = useTranslation('auth')
  const { theme, setTheme } = useTheme()

  return (
    <div className="tms-segmented tms-segmented-theme" role="group" aria-label={t('theme')}>
      {THEME_NAMES.map((name) => {
        const selected = name === theme
        return (
          <button
            key={name}
            type="button"
            className={`tms-segmented-option${selected ? ' is-selected' : ''}`}
            aria-pressed={selected}
            onClick={() => setTheme(name)}
          >
            <span className={`tms-theme-swatch tms-theme-swatch-${name}`} aria-hidden="true" />
            {t(THEME_LABEL[name])}
          </button>
        )
      })}
    </div>
  )
}
