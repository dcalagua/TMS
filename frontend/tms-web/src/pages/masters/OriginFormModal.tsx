import { useState } from 'react'
import { useForm, type Validate } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import {
  createOrigin,
  ORIGIN_TYPE_LABELS,
  ORIGIN_TYPES,
  updateOrigin,
  type OriginRequest,
  type OriginType,
  type OriginView,
} from '../../shared/api/originsApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'

interface OriginFormValues {
  code: string
  name: string
  type: OriginType
  address: string
  latitude: string
  longitude: string
  timeZone: string
  externalReference: string
}

interface OriginFormModalProps {
  companyId: string
  /** `null` creates a new origin; otherwise the form edits this one. */
  origin: OriginView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof OriginFormValues>([
  'code', 'name', 'type', 'address', 'latitude', 'longitude', 'timeZone', 'externalReference',
])

// Intl.supportedValuesOf is widely available in evergreen browsers; an empty list just means
// the time zone field falls back to plain free text with the validate() check below.
const TIME_ZONES: string[] = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : []

function isValidTimeZone(value: string): boolean {
  try {
    Intl.DateTimeFormat(undefined, { timeZone: value })
    return true
  } catch {
    return false
  }
}

/** Create and edit share one form: the fields and validation are identical (see `OriginRequest`). */
export function OriginFormModal({ companyId, origin, onClose, onSaved }: OriginFormModalProps) {
  const isEdit = origin !== null
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<OriginFormValues>({
    defaultValues: {
      code: origin?.code ?? '',
      name: origin?.name ?? '',
      type: origin?.type ?? 'OTHER',
      address: origin?.address ?? '',
      latitude: origin?.latitude?.toString() ?? '',
      longitude: origin?.longitude?.toString() ?? '',
      timeZone: origin?.timeZone ?? '',
      externalReference: origin?.externalReference ?? '',
    },
  })

  const validateLatitude: Validate<string, OriginFormValues> = (value, formValues) => {
    if (value.trim() === '') {
      return formValues.longitude.trim() === '' || 'Provide both latitude and longitude, or leave both blank'
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return 'Must be a number'
    if (parsed < -90 || parsed > 90) return 'Must be between -90 and 90'
    return true
  }

  const validateLongitude: Validate<string, OriginFormValues> = (value, formValues) => {
    if (value.trim() === '') {
      return formValues.latitude.trim() === '' || 'Provide both latitude and longitude, or leave both blank'
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return 'Must be a number'
    if (parsed < -180 || parsed > 180) return 'Must be between -180 and 180'
    return true
  }

  async function onSubmit(values: OriginFormValues) {
    setFormError(null)
    const request: OriginRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      type: values.type,
      address: values.address.trim() || null,
      latitude: values.latitude.trim() === '' ? null : Number(values.latitude),
      longitude: values.longitude.trim() === '' ? null : Number(values.longitude),
      timeZone: values.timeZone.trim(),
      externalReference: values.externalReference.trim() || null,
    }

    try {
      if (isEdit) {
        await updateOrigin(companyId, origin.id, request)
      } else {
        await createOrigin(companyId, request)
      }
      onSaved()
    } catch (error) {
      const apiError = error as ApiError
      if (apiError.fieldErrors.length > 0) {
        const unmatched: string[] = []
        for (const fieldError of apiError.fieldErrors) {
          if (KNOWN_FIELDS.has(fieldError.field as keyof OriginFormValues)) {
            setError(fieldError.field as keyof OriginFormValues, { message: fieldError.message })
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
      <div className="modal-dialog modal-lg" role="document">
        <div className="modal-content">
          <form onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <div className="modal-header">
              <h5 className="modal-title">{isEdit ? 'Edit origin' : 'New origin'}</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              {formError && (
                <div className="alert alert-danger py-2 small" role="alert">
                  {formError}
                </div>
              )}
              <div className="row">
                <div className="col-md-4">
                  <FormField label="Code" htmlFor="origin-code" error={errors.code?.message} required>
                    <input
                      id="origin-code"
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
                </div>
                <div className="col-md-8">
                  <FormField label="Name" htmlFor="origin-name" error={errors.name?.message} required>
                    <input
                      id="origin-name"
                      className={`form-control${errors.name ? ' is-invalid' : ''}`}
                      {...register('name', {
                        required: 'Name is required',
                        maxLength: { value: 200, message: 'Must be 200 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-4">
                  <FormField label="Type" htmlFor="origin-type" error={errors.type?.message} required>
                    <select id="origin-type" className="form-select" {...register('type', { required: true })}>
                      {ORIGIN_TYPES.map((type) => (
                        <option key={type} value={type}>
                          {ORIGIN_TYPE_LABELS[type]}
                        </option>
                      ))}
                    </select>
                  </FormField>
                </div>
                <div className="col-md-8">
                  <FormField label="Address" htmlFor="origin-address" error={errors.address?.message}>
                    <input
                      id="origin-address"
                      className={`form-control${errors.address ? ' is-invalid' : ''}`}
                      {...register('address', { maxLength: { value: 500, message: 'Must be 500 characters or fewer' } })}
                    />
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-3">
                  <FormField label="Latitude" htmlFor="origin-latitude" error={errors.latitude?.message}>
                    <input
                      id="origin-latitude"
                      type="text"
                      inputMode="decimal"
                      placeholder="-12.046374"
                      className={`form-control${errors.latitude ? ' is-invalid' : ''}`}
                      {...register('latitude', { validate: validateLatitude })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Longitude" htmlFor="origin-longitude" error={errors.longitude?.message}>
                    <input
                      id="origin-longitude"
                      type="text"
                      inputMode="decimal"
                      placeholder="-77.042793"
                      className={`form-control${errors.longitude ? ' is-invalid' : ''}`}
                      {...register('longitude', { validate: validateLongitude })}
                    />
                  </FormField>
                </div>
                <div className="col-md-6">
                  <FormField label="Time zone" htmlFor="origin-timezone" error={errors.timeZone?.message} required>
                    <input
                      id="origin-timezone"
                      list="tms-time-zones"
                      placeholder="America/Lima"
                      className={`form-control${errors.timeZone ? ' is-invalid' : ''}`}
                      {...register('timeZone', {
                        required: 'Time zone is required',
                        validate: (value) => isValidTimeZone(value.trim()) || 'Must be a valid IANA time zone, e.g. America/Lima',
                      })}
                    />
                    <datalist id="tms-time-zones">
                      {TIME_ZONES.map((zone) => (
                        <option key={zone} value={zone} />
                      ))}
                    </datalist>
                  </FormField>
                </div>
              </div>
              <FormField label="External reference" htmlFor="origin-external-reference" error={errors.externalReference?.message}>
                <input
                  id="origin-external-reference"
                  className={`form-control${errors.externalReference ? ' is-invalid' : ''}`}
                  placeholder="Optional EWM or external system code"
                  {...register('externalReference', { maxLength: { value: 100, message: 'Must be 100 characters or fewer' } })}
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
