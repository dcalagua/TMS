import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { createZone, updateZone, type ZoneRequest, type ZoneView } from '../../shared/api/zonesApi'
import { FormField } from '../../shared/ui/components/FormField'

interface ZoneFormValues {
  code: string
  name: string
  description: string
}

interface ZoneFormModalProps {
  companyId: string
  /** `null` creates a new zone; otherwise the form edits this one. */
  zone: ZoneView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof ZoneFormValues>(['code', 'name', 'description'])

/** Create and edit share one form; see `OriginFormModal` for the same pattern with more fields. */
export function ZoneFormModal({ companyId, zone, onClose, onSaved }: ZoneFormModalProps) {
  const isEdit = zone !== null
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ZoneFormValues>({
    defaultValues: {
      code: zone?.code ?? '',
      name: zone?.name ?? '',
      description: zone?.description ?? '',
    },
  })

  async function onSubmit(values: ZoneFormValues) {
    setFormError(null)
    const request: ZoneRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      description: values.description.trim() || null,
    }

    try {
      if (isEdit) {
        await updateZone(companyId, zone.id, request)
      } else {
        await createZone(companyId, request)
      }
      onSaved()
    } catch (error) {
      const apiError = error as ApiError
      if (apiError.fieldErrors.length > 0) {
        const unmatched: string[] = []
        for (const fieldError of apiError.fieldErrors) {
          if (KNOWN_FIELDS.has(fieldError.field as keyof ZoneFormValues)) {
            setError(fieldError.field as keyof ZoneFormValues, { message: fieldError.message })
          } else {
            unmatched.push(fieldError.message)
          }
        }
        setFormError(unmatched.length > 0 ? unmatched.join(' ') : 'Please correct the highlighted fields.')
      } else {
        setFormError(describeApiError(apiError))
      }
    }
  }

  return (
    <div
      className="modal d-block"
      tabIndex={-1}
      role="dialog"
      aria-modal="true"
      onKeyDown={(event) => {
        if (event.key === 'Escape') onClose()
      }}
    >
      <div className="modal-dialog" role="document">
        <div className="modal-content">
          <form onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <div className="modal-header">
              <h5 className="modal-title">{isEdit ? 'Edit zone' : 'New zone'}</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              {formError && (
                <div className="alert alert-danger py-2 small" role="alert">
                  {formError}
                </div>
              )}
              <FormField label="Code" htmlFor="zone-code" error={errors.code?.message} required>
                <input
                  id="zone-code"
                  className={`form-control${errors.code ? ' is-invalid' : ''}`}
                  {...register('code', {
                    required: 'Code is required',
                    maxLength: { value: 32, message: 'Must be 32 characters or fewer' },
                    pattern: {
                      value: /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/,
                      message: 'Letters, digits, underscore or hyphen only',
                    },
                  })}
                />
              </FormField>
              <FormField label="Name" htmlFor="zone-name" error={errors.name?.message} required>
                <input
                  id="zone-name"
                  className={`form-control${errors.name ? ' is-invalid' : ''}`}
                  {...register('name', {
                    required: 'Name is required',
                    maxLength: { value: 200, message: 'Must be 200 characters or fewer' },
                  })}
                />
              </FormField>
              <FormField label="Description" htmlFor="zone-description" error={errors.description?.message}>
                <textarea
                  id="zone-description"
                  rows={3}
                  className={`form-control${errors.description ? ' is-invalid' : ''}`}
                  {...register('description', { maxLength: { value: 1000, message: 'Must be 1000 characters or fewer' } })}
                />
              </FormField>
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-outline-secondary" onClick={onClose}>
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={isSubmitting}>
                {isSubmitting ? 'Saving...' : 'Save'}
              </button>
            </div>
          </form>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </div>
  )
}
