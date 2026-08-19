import type { ReactNode } from 'react'

interface FormFieldProps {
  label: string
  htmlFor: string
  error?: string
  required?: boolean
  children: ReactNode
}

/** Label + control + validation-message layout for a React Hook Form field. The control is
 * passed as `children` (typically `<input {...register('x')} className="form-control" />`)
 * so this stays input-type agnostic. */
export function FormField({ label, htmlFor, error, required, children }: FormFieldProps) {
  return (
    <div className="mb-3">
      <label htmlFor={htmlFor} className="form-label">
        {label}
        {required && <span className="text-danger"> *</span>}
      </label>
      {children}
      {error && (
        <div className="invalid-feedback d-block" role="alert">
          {error}
        </div>
      )}
    </div>
  )
}
