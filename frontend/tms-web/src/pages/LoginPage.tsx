import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../shared/auth/AuthContext'
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
  const { status, signIn } = useAuth()
  const location = useLocation()
  const [formError, setFormError] = useState<string | null>(null)
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
      setFormError(result.message ?? 'Sign-in failed. Check your credentials and try again.')
    }
  }

  return (
    <div className="min-vh-100 d-flex align-items-center justify-content-center bg-body-tertiary">
      <div className="card shadow-sm" style={{ width: 380 }}>
        <div className="card-body p-4">
          <h1 className="h4 mb-1">
            TMS <span className="text-secondary fw-normal">by EBIM</span>
          </h1>
          <p className="text-body-secondary small mb-4">Sign in to continue.</p>

          {formError && (
            <div className="alert alert-danger py-2 small" role="alert">
              {formError}
            </div>
          )}

          <form onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <FormField label="Email" htmlFor="email" error={errors.email?.message} required>
              <input
                id="email"
                type="email"
                autoComplete="username"
                className={`form-control${errors.email ? ' is-invalid' : ''}`}
                {...register('email', { required: 'Email is required' })}
              />
            </FormField>

            <FormField label="Password" htmlFor="password" error={errors.password?.message} required>
              <input
                id="password"
                type="password"
                autoComplete="current-password"
                className={`form-control${errors.password ? ' is-invalid' : ''}`}
                {...register('password', { required: 'Password is required' })}
              />
            </FormField>

            <button type="submit" className="btn btn-primary w-100" disabled={isSubmitting}>
              {isSubmitting ? 'Signing in...' : 'Sign in'}
            </button>
          </form>
        </div>
      </div>
    </div>
  )
}
