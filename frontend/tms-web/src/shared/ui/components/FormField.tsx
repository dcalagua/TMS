import type { ReactNode } from 'react'

interface FormFieldProps {
  label: string
  htmlFor: string
  error?: string
  required?: boolean
  /** One line under the control: a format, a unit, a consequence. Never a restatement of the label. */
  help?: string
  children: ReactNode
}

/**
 * Label + control + validation message layout for a React Hook Form field. The control is
 * passed as `children` (typically `<input {...register('x')} className="form-control" />`) so
 * this stays input-type agnostic.
 *
 * The reading order is label, control, help, error, and each step is set one level quieter than
 * the one above it. Spacing comes from `.tms-field` rather than a Bootstrap margin utility, so
 * a drawer can set its own field rhythm without every form having to agree to it.
 */
export function FormField({ label, htmlFor, error, required, help, children }: FormFieldProps) {
  const helpId = help ? `${htmlFor}-help` : undefined

  return (
    <div className="tms-field mb-3">
      <label htmlFor={htmlFor} className="form-label">
        {label}
        {required && <span className="text-danger"> *</span>}
      </label>
      {children}
      {help && (
        <span className="tms-field-help" id={helpId}>
          {help}
        </span>
      )}
      {error && (
        <div className="invalid-feedback d-block" role="alert">
          {error}
        </div>
      )}
    </div>
  )
}
