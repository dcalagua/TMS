import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../shared/auth/AuthContext'
import { LanguageSwitcher } from '../shared/ui/LanguageSwitcher'
import { FormField } from '../shared/ui/components/FormField'

interface LoginFormValues {
  email: string
  password: string
}

interface LocationState {
  from?: { pathname: string }
}

/** What the product does, in the panel's own words. Three is the count that fits without the
 * panel turning into a feature list nobody reads. */
const FEATURES = [
  { icon: 'bi-box-seam', titleKey: 'brand.feature1.title', textKey: 'brand.feature1.text' },
  { icon: 'bi-sliders', titleKey: 'brand.feature2.title', textKey: 'brand.feature2.text' },
  { icon: 'bi-buildings', titleKey: 'brand.feature3.title', textKey: 'brand.feature3.text' },
] as const

/**
 * Sign-in screen. The only place the app talks to Supabase Auth directly, through
 * `useAuth().signIn` - no business call happens here.
 *
 * The composition is a single centred card holding two panels, not a full-bleed split. A split
 * that runs edge to edge gives the branding a column it cannot fill and strands the form in the
 * middle of a large empty field; a contained card of a fixed maximum width holds its own
 * proportions on a 1280px laptop and a 2560px monitor alike, and reads as an object placed on
 * the page rather than as two coloured halves of it.
 *
 * Below `lg` the brand panel is dropped rather than stacked: on a phone it would only push the
 * fields under the fold. The brand is set in type - there is no EBIM logo asset in the repo,
 * and inventing one would be worse than a wordmark.
 */
export function LoginPage() {
  const { t } = useTranslation(['auth', 'common'])
  const { status, signIn } = useAuth()
  const location = useLocation()
  const [formError, setFormError] = useState<string | null>(null)
  const [showPassword, setShowPassword] = useState(false)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>()

  if (status === 'signedIn') {
    const redirectTo = (location.state as LocationState | null)?.from?.pathname ?? '/'
    return <Navigate to={redirectTo} replace />
  }

  async function onSubmit(values: LoginFormValues) {
    setFormError(null)
    const result = await signIn(values.email, values.password)
    if (!result.ok) {
      setFormError(result.message ?? t('login.failed'))
    }
  }

  return (
    <div className="tms-login">
      {/* Page furniture, not card furniture: the language switch belongs to the visit, not to
          the form, so it sits outside the card the way a browser control would. */}
      <div className="tms-login-utility">
        <LanguageSwitcher />
      </div>

      <div className="tms-login-card">
        <aside className="tms-login-brand d-none d-lg-flex">
          <div className="tms-login-brand-top">
            <span className="tms-login-logo" aria-hidden="true">
              TMS
            </span>
            <p className="tms-login-wordmark">
              TMS <span>by EBIM</span>
            </p>
            <p className="tms-login-kicker">{t('brand.kicker')}</p>
          </div>

          <div>
            <h2 className="tms-login-headline">{t('brand.headline')}</h2>
            <p className="tms-login-lead">{t('brand.lead')}</p>

            <ul className="tms-login-features">
              {FEATURES.map((feature) => (
                <li key={feature.icon} className="tms-login-feature">
                  <span className="tms-login-feature-icon" aria-hidden="true">
                    <i className={`bi ${feature.icon}`} />
                  </span>
                  <span>
                    <span className="tms-login-feature-title">{t(feature.titleKey)}</span>
                    <span className="tms-login-feature-text">{t(feature.textKey)}</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>

          <div className="tms-login-brand-foot">
            <p className="tms-login-trust">
              <i className="bi bi-shield-check" aria-hidden="true" />
              {t('brand.trust')}
            </p>
            <p className="tms-login-suite">{t('brand.suite')}</p>
          </div>
        </aside>

        <main className="tms-login-panel">
          {/* The wordmark appears here only where the brand panel is absent, so a phone still
              knows what it is signing in to. */}
          <span className="tms-login-panel-brand d-lg-none">
            <span className="tms-brand-mark" aria-hidden="true">
              TMS
            </span>
            <span className="tms-brand">
              TMS <span className="tms-brand-accent">by EBIM</span>
            </span>
          </span>

          <h1 className="tms-login-title">{t('login.welcome')}</h1>
          <p className="tms-login-subtitle">{t('login.subtitle')}</p>

          {formError && (
            <div className="alert alert-danger d-flex align-items-start gap-2 py-2 small" role="alert">
              <i className="bi bi-exclamation-triangle-fill mt-1" aria-hidden="true" />
              <span>{formError}</span>
            </div>
          )}

          <form onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <FormField label={t('login.email')} htmlFor="email" error={errors.email?.message} required>
              <input
                id="email"
                type="email"
                autoComplete="username"
                autoFocus
                placeholder={t('login.emailPlaceholder')}
                className={`form-control form-control-lg${errors.email ? ' is-invalid' : ''}`}
                {...register('email', { required: t('login.emailRequired') })}
              />
            </FormField>

            <FormField label={t('login.password')} htmlFor="password" error={errors.password?.message} required>
              <div className="input-group">
                <input
                  id="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  className={`form-control form-control-lg${errors.password ? ' is-invalid' : ''}`}
                  {...register('password', { required: t('login.passwordRequired') })}
                />
                <button
                  type="button"
                  className="btn btn-outline-secondary"
                  onClick={() => setShowPassword((visible) => !visible)}
                  aria-label={showPassword ? t('login.hidePassword') : t('login.showPassword')}
                  aria-pressed={showPassword}
                >
                  <i className={showPassword ? 'bi bi-eye-slash' : 'bi bi-eye'} aria-hidden="true" />
                </button>
              </div>
            </FormField>

            <button
              type="submit"
              className="btn btn-primary btn-lg w-100 d-flex align-items-center justify-content-center gap-2 mt-1"
              disabled={isSubmitting}
            >
              {isSubmitting && <span className="spinner-border spinner-border-sm" aria-hidden="true" />}
              {isSubmitting ? t('login.submitting') : t('login.submit')}
            </button>
          </form>

          <p className="tms-login-help">{t('login.help')}</p>
        </main>
      </div>
    </div>
  )
}
