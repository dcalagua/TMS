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

/** Sign-in screen. The only place the app talks to Supabase Auth directly, through
 * `useAuth().signIn` - no business call happens here. */
export function LoginPage() {
  const { t } = useTranslation('auth')
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
    <div className="min-vh-100 d-flex align-items-center justify-content-center bg-body-tertiary p-3">
      <div className="card shadow-sm w-100" style={{ maxWidth: 440 }}>
        <div className="card-body p-4">
          <div className="d-flex align-items-start justify-content-between gap-2 mb-3">
            <div>
              <h1 className="h4 mb-1">
                TMS <span className="text-secondary fw-normal">by EBIM</span>
              </h1>
              <p className="text-body-secondary small mb-0">{t('login.subtitle')}</p>
            </div>
            <LanguageSwitcher />
          </div>

          {formError && (
            <div className="alert alert-danger py-2 small" role="alert">
              {formError}
            </div>
          )}

          <form onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <FormField label={t('login.email')} htmlFor="email" error={errors.email?.message} required>
              <input
                id="email"
                type="email"
                autoComplete="username"
                className={`form-control${errors.email ? ' is-invalid' : ''}`}
                {...register('email', { required: t('login.emailRequired') })}
              />
            </FormField>

            <FormField label={t('login.password')} htmlFor="password" error={errors.password?.message} required>
              <div className="input-group">
                <input
                  id="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  className={`form-control${errors.password ? ' is-invalid' : ''}`}
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

            <button type="submit" className="btn btn-primary w-100" disabled={isSubmitting}>
              {isSubmitting ? t('login.submitting') : t('login.submit')}
            </button>
          </form>
        </div>
      </div>
    </div>
  )
}
