import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, type Validate } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import {
  createDestination,
  DESTINATION_TYPE_LABELS,
  DESTINATION_TYPES,
  updateDestination,
  type DestinationRequest,
  type DestinationType,
  type DestinationView,
} from '../../shared/api/destinationsApi'
import { fetchZones } from '../../shared/api/zonesApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'

interface DestinationFormValues {
  code: string
  name: string
  type: DestinationType
  zoneId: string
  address: string
  addressReference: string
  district: string
  province: string
  department: string
  country: string
  latitude: string
  longitude: string
  serviceTimeMinutes: string
  externalReference: string
}

interface DestinationFormModalProps {
  companyId: string
  /** `null` creates a new destination; otherwise the form edits this one. */
  destination: DestinationView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof DestinationFormValues>([
  'code', 'name', 'type', 'zoneId', 'address', 'addressReference', 'district', 'province', 'department',
  'country', 'latitude', 'longitude', 'serviceTimeMinutes', 'externalReference',
])

/** Create and edit share one form: the fields and validation are identical (see `DestinationRequest`). */
export function DestinationFormModal({ companyId, destination, onClose, onSaved }: DestinationFormModalProps) {
  const isEdit = destination !== null
  const [formError, setFormError] = useState<string | null>(null)

  const zonesQuery = useQuery({
    queryKey: ['zones-for-destination-form', companyId],
    queryFn: ({ signal }) => fetchZones({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const zones = zonesQuery.data?.content ?? []

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<DestinationFormValues>({
    defaultValues: {
      code: destination?.code ?? '',
      name: destination?.name ?? '',
      type: destination?.type ?? 'CUSTOMER',
      zoneId: destination?.zoneId ?? '',
      address: destination?.address ?? '',
      addressReference: destination?.addressReference ?? '',
      district: destination?.district ?? '',
      province: destination?.province ?? '',
      department: destination?.department ?? '',
      country: destination?.country ?? 'PE',
      latitude: destination?.latitude?.toString() ?? '',
      longitude: destination?.longitude?.toString() ?? '',
      serviceTimeMinutes: destination?.serviceTimeMinutes?.toString() ?? '0',
      externalReference: destination?.externalReference ?? '',
    },
  })

  const validateLatitude: Validate<string, DestinationFormValues> = (value, formValues) => {
    if (value.trim() === '') {
      return formValues.longitude.trim() === '' || 'Provide both latitude and longitude, or leave both blank'
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return 'Must be a number'
    if (parsed < -90 || parsed > 90) return 'Must be between -90 and 90'
    return true
  }

  const validateLongitude: Validate<string, DestinationFormValues> = (value, formValues) => {
    if (value.trim() === '') {
      return formValues.latitude.trim() === '' || 'Provide both latitude and longitude, or leave both blank'
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return 'Must be a number'
    if (parsed < -180 || parsed > 180) return 'Must be between -180 and 180'
    return true
  }

  async function onSubmit(values: DestinationFormValues) {
    setFormError(null)
    const request: DestinationRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      type: values.type,
      zoneId: values.zoneId || null,
      address: values.address.trim() || null,
      addressReference: values.addressReference.trim() || null,
      district: values.district.trim() || null,
      province: values.province.trim() || null,
      department: values.department.trim() || null,
      country: values.country.trim(),
      latitude: values.latitude.trim() === '' ? null : Number(values.latitude),
      longitude: values.longitude.trim() === '' ? null : Number(values.longitude),
      serviceTimeMinutes: values.serviceTimeMinutes.trim() === '' ? 0 : Number(values.serviceTimeMinutes),
      externalReference: values.externalReference.trim() || null,
    }

    try {
      if (isEdit) {
        await updateDestination(companyId, destination.id, request)
      } else {
        await createDestination(companyId, request)
      }
      onSaved()
    } catch (error) {
      const apiError = error as ApiError
      if (apiError.fieldErrors.length > 0) {
        const unmatched: string[] = []
        for (const fieldError of apiError.fieldErrors) {
          if (KNOWN_FIELDS.has(fieldError.field as keyof DestinationFormValues)) {
            setError(fieldError.field as keyof DestinationFormValues, { message: fieldError.message })
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
              <h5 className="modal-title">{isEdit ? 'Edit destination' : 'New destination'}</h5>
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
                  <FormField label="Code" htmlFor="destination-code" error={errors.code?.message} required>
                    <input
                      id="destination-code"
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
                <div className="col-md-6">
                  <FormField label="Name" htmlFor="destination-name" error={errors.name?.message} required>
                    <input
                      id="destination-name"
                      className={`form-control${errors.name ? ' is-invalid' : ''}`}
                      {...register('name', {
                        required: 'Name is required',
                        maxLength: { value: 200, message: 'Must be 200 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Type" htmlFor="destination-type" error={errors.type?.message} required>
                    <select id="destination-type" className="form-select" {...register('type', { required: true })}>
                      {DESTINATION_TYPES.map((type) => (
                        <option key={type} value={type}>
                          {DESTINATION_TYPE_LABELS[type]}
                        </option>
                      ))}
                    </select>
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-4">
                  <FormField label="Zone" htmlFor="destination-zone" error={errors.zoneId?.message}>
                    <select id="destination-zone" className="form-select" {...register('zoneId')}>
                      <option value="">No zone</option>
                      {zones.map((zone) => (
                        <option key={zone.id} value={zone.id}>
                          {zone.name}
                        </option>
                      ))}
                    </select>
                  </FormField>
                </div>
                <div className="col-md-5">
                  <FormField label="Address" htmlFor="destination-address" error={errors.address?.message}>
                    <input
                      id="destination-address"
                      className={`form-control${errors.address ? ' is-invalid' : ''}`}
                      {...register('address', { maxLength: { value: 500, message: 'Must be 500 characters or fewer' } })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField
                    label="Reference"
                    htmlFor="destination-address-reference"
                    error={errors.addressReference?.message}
                  >
                    <input
                      id="destination-address-reference"
                      placeholder="e.g. blue gate"
                      className={`form-control${errors.addressReference ? ' is-invalid' : ''}`}
                      {...register('addressReference', {
                        maxLength: { value: 300, message: 'Must be 300 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-3">
                  <FormField label="District" htmlFor="destination-district" error={errors.district?.message}>
                    <input
                      id="destination-district"
                      className={`form-control${errors.district ? ' is-invalid' : ''}`}
                      {...register('district', { maxLength: { value: 120, message: 'Must be 120 characters or fewer' } })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Province" htmlFor="destination-province" error={errors.province?.message}>
                    <input
                      id="destination-province"
                      className={`form-control${errors.province ? ' is-invalid' : ''}`}
                      {...register('province', { maxLength: { value: 120, message: 'Must be 120 characters or fewer' } })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Department" htmlFor="destination-department" error={errors.department?.message}>
                    <input
                      id="destination-department"
                      className={`form-control${errors.department ? ' is-invalid' : ''}`}
                      {...register('department', {
                        maxLength: { value: 120, message: 'Must be 120 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Country" htmlFor="destination-country" error={errors.country?.message} required>
                    <input
                      id="destination-country"
                      className={`form-control${errors.country ? ' is-invalid' : ''}`}
                      {...register('country', {
                        required: 'Country is required',
                        maxLength: { value: 60, message: 'Must be 60 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-3">
                  <FormField label="Latitude" htmlFor="destination-latitude" error={errors.latitude?.message}>
                    <input
                      id="destination-latitude"
                      type="text"
                      inputMode="decimal"
                      placeholder="-12.046374"
                      className={`form-control${errors.latitude ? ' is-invalid' : ''}`}
                      {...register('latitude', { validate: validateLatitude })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Longitude" htmlFor="destination-longitude" error={errors.longitude?.message}>
                    <input
                      id="destination-longitude"
                      type="text"
                      inputMode="decimal"
                      placeholder="-77.042793"
                      className={`form-control${errors.longitude ? ' is-invalid' : ''}`}
                      {...register('longitude', { validate: validateLongitude })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField
                    label="Service time (min)"
                    htmlFor="destination-service-time"
                    error={errors.serviceTimeMinutes?.message}
                    required
                  >
                    <input
                      id="destination-service-time"
                      type="number"
                      min={0}
                      className={`form-control${errors.serviceTimeMinutes ? ' is-invalid' : ''}`}
                      {...register('serviceTimeMinutes', {
                        required: 'Service time is required',
                        min: { value: 0, message: 'Must be zero or greater' },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField
                    label="External reference"
                    htmlFor="destination-external-reference"
                    error={errors.externalReference?.message}
                  >
                    <input
                      id="destination-external-reference"
                      placeholder="Optional EWM code"
                      className={`form-control${errors.externalReference ? ' is-invalid' : ''}`}
                      {...register('externalReference', {
                        maxLength: { value: 100, message: 'Must be 100 characters or fewer' },
                      })}
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
