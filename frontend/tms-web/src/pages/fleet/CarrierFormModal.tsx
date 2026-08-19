import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { createCarrier, updateCarrier, type CarrierRequest, type CarrierView } from '../../shared/api/carriersApi'
import { FormField } from '../../shared/ui/components/FormField'

interface CarrierFormValues {
  code: string
  businessName: string
  taxIdType: string
  taxIdValue: string
  contactName: string
  phone: string
  email: string
}

interface CarrierFormModalProps {
  companyId: string
  /** `null` creates a new carrier; otherwise the form edits this one. */
  carrier: CarrierView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof CarrierFormValues>([
  'code', 'businessName', 'taxIdType', 'taxIdValue', 'contactName', 'phone', 'email',
])

/** Create and edit share one form; see `ZoneFormModal` (masters) for the same pattern. */
export function CarrierFormModal({ companyId, carrier, onClose, onSaved }: CarrierFormModalProps) {
  const isEdit = carrier !== null
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<CarrierFormValues>({
    defaultValues: {
      code: carrier?.code ?? '',
      businessName: carrier?.businessName ?? '',
      taxIdType: carrier?.taxIdType ?? 'RUC',
      taxIdValue: carrier?.taxIdValue ?? '',
      contactName: carrier?.contactName ?? '',
      phone: carrier?.phone ?? '',
      email: carrier?.email ?? '',
    },
  })

  async function onSubmit(values: CarrierFormValues) {
    setFormError(null)
    const request: CarrierRequest = {
      code: values.code.trim(),
      businessName: values.businessName.trim(),
      taxIdType: values.taxIdType.trim(),
      taxIdValue: values.taxIdValue.trim(),
      contactName: values.contactName.trim() || null,
      phone: values.phone.trim() || null,
      email: values.email.trim() || null,
    }

    try {
      if (isEdit) {
        await updateCarrier(companyId, carrier.id, request)
      } else {
        await createCarrier(companyId, request)
      }
      onSaved()
    } catch (error) {
      const apiError = error as ApiError
      if (apiError.fieldErrors.length > 0) {
        const unmatched: string[] = []
        for (const fieldError of apiError.fieldErrors) {
          if (KNOWN_FIELDS.has(fieldError.field as keyof CarrierFormValues)) {
            setError(fieldError.field as keyof CarrierFormValues, { message: fieldError.message })
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
              <h5 className="modal-title">{isEdit ? 'Edit carrier' : 'New carrier'}</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              {formError && (
                <div className="alert alert-danger py-2 small" role="alert">
                  {formError}
                </div>
              )}
              <div className="row">
                <div className="col-md-3">
                  <FormField label="Code" htmlFor="carrier-code" error={errors.code?.message} required>
                    <input
                      id="carrier-code"
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
                <div className="col-md-9">
                  <FormField label="Business name" htmlFor="carrier-business-name" error={errors.businessName?.message} required>
                    <input
                      id="carrier-business-name"
                      className={`form-control${errors.businessName ? ' is-invalid' : ''}`}
                      {...register('businessName', {
                        required: 'Business name is required',
                        maxLength: { value: 200, message: 'Must be 200 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-3">
                  <FormField label="Tax id type" htmlFor="carrier-tax-id-type" error={errors.taxIdType?.message} required>
                    <input
                      id="carrier-tax-id-type"
                      placeholder="RUC, DNI, ..."
                      className={`form-control${errors.taxIdType ? ' is-invalid' : ''}`}
                      {...register('taxIdType', {
                        required: 'Tax id type is required',
                        maxLength: { value: 32, message: 'Must be 32 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-md-4">
                  <FormField label="Tax id value" htmlFor="carrier-tax-id-value" error={errors.taxIdValue?.message} required>
                    <input
                      id="carrier-tax-id-value"
                      className={`form-control${errors.taxIdValue ? ' is-invalid' : ''}`}
                      {...register('taxIdValue', {
                        required: 'Tax id value is required',
                        maxLength: { value: 64, message: 'Must be 64 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-md-5">
                  <FormField label="Contact name" htmlFor="carrier-contact-name" error={errors.contactName?.message}>
                    <input
                      id="carrier-contact-name"
                      className={`form-control${errors.contactName ? ' is-invalid' : ''}`}
                      {...register('contactName', { maxLength: { value: 200, message: 'Must be 200 characters or fewer' } })}
                    />
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-4">
                  <FormField label="Phone" htmlFor="carrier-phone" error={errors.phone?.message}>
                    <input
                      id="carrier-phone"
                      className={`form-control${errors.phone ? ' is-invalid' : ''}`}
                      {...register('phone', { maxLength: { value: 32, message: 'Must be 32 characters or fewer' } })}
                    />
                  </FormField>
                </div>
                <div className="col-md-8">
                  <FormField label="Email" htmlFor="carrier-email" error={errors.email?.message}>
                    <input
                      id="carrier-email"
                      type="email"
                      className={`form-control${errors.email ? ' is-invalid' : ''}`}
                      {...register('email', { maxLength: { value: 200, message: 'Must be 200 characters or fewer' } })}
                    />
                  </FormField>
                </div>
              </div>
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
