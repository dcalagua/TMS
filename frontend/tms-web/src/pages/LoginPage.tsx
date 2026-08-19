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

/**
 * Sign-in screen. The only place the app talks to Supabase Auth directly, through
 * `useAuth().signIn` - no business call happens here.
 *
 * Two panels on a wide screen: an identity panel that says what the product is, and the form.
 * Below `lg` the identity panel is dropped entirely rather than stacked - on a phone it would
 * only push the fields below the fold. The brand is set in type: there is no EBIM logo asset
 * yet, and inventing one would be worse than a wordmark.
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
    <div className="min-vh-100 d-flex tms-min-w-0" style={{ backgroundColor: 'var(--tms-body-bg)' }}>
      <aside
        className="d-none d-lg-flex flex-column justify-content-between p-5 text-white"
        style={{
          width: '42%',
          maxWidth: '34rem',
          background: 'linear-gradient(160deg, var(--tms-sidebar-bg) 0%, #1b2b4d 55%, #1d4ed8 160%)',
        }}
      >
        <div className="d-flex align-items-center gap-2">
          <span className="tms-brand-mark" aria-hidden="true">
            TMS
          </span>
          <span className="fs-5 fw-semibold">
            TMS <span className="fw-normal text-white-50">by EBIM</span>
          </span>
        </div>

        <div>
          {/* Headings inherit `--bs-heading-color`, which is the dark body colour; on this
              navy panel that would fail contrast, so the colour is set explicitly. */}
          <h2 className="fw-semibold mb-3 text-white" style={{ fontSize: '1.75rem', lineHeight: 1.25 }}>
            {t('brand.headline')}
          </h2>
          <ul className="list-unstyled mb-0 d-grid gap-2 text-white-50">
            {(['brand.point1', 'brand.point2', 'brand.point3'] as const).map((key) => (
              <li key={key} className="d-flex align-items-start gap-2">
                <i className="bi bi-check2-circle mt-1" style={{ color: 'var(--tms-sidebar-accent)' }} aria-hidden="true" />
                <span>{t(key)}</span>
              </li>
            ))}
          </ul>
        </div>

        <p className="small mb-0 text-white-50">{t('brand.tagline', { ns: 'common' })}</p>
      </aside>

      <main className="flex-grow-1 d-flex align-items-center justify-content-center p-3 p-sm-4 tms-min-w-0">
        <div className="w-100" style={{ maxWidth: 440 }}>
          <div className="d-flex align-items-center justify-content-between gap-2 mb-3">
            <span className="d-flex d-lg-none align-items-center gap-2 tms-brand">
              <span className="tms-brand-mark" aria-hidden="true">
                TMS
              </span>
              <span>
                TMS <span className="tms-brand-accent">by EBIM</span>
              </span>
            </span>
            <div className="ms-auto">
              <LanguageSwitcher />
            </div>
          </div>

          <div className="tms-card">
            <div className="p-4 p-sm-5">
              <h1 className="h4 mb-1">{t('login.welcome')}</h1>
              <p className="text-body-secondary mb-4">{t('login.subtitle')}</p>

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
                  className="btn btn-primary btn-lg w-100 d-flex align-items-center justify-content-center gap-2"
                  disabled={isSubmitting}
                >
                  {isSubmitting && <span className="spinner-border spinner-border-sm" aria-hidden="true" />}
                  {isSubmitting ? t('login.submitting') : t('login.submit')}
                </button>
              </form>
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}
