import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, type Validate } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { fetchCarriers } from '../../shared/api/carriersApi'
import { fetchVehicleTypes } from '../../shared/api/vehicleTypesApi'
import {
  createVehicle,
  updateVehicle,
  VEHICLE_AVAILABILITY_STATUS_LABELS,
  VEHICLE_AVAILABILITY_STATUSES,
  type VehicleAvailabilityStatus,
  type VehicleRequest,
  type VehicleView,
} from '../../shared/api/vehiclesApi'
import { FormField } from '../../shared/ui/components/FormField'

interface VehicleFormValues {
  code: string
  licensePlate: string
  carrierId: string
  vehicleTypeId: string
  maxWeightOverrideKg: string
  maxVolumeOverrideM3: string
  maxPalletsOverride: string
  availabilityStatus: VehicleAvailabilityStatus
}

interface VehicleFormModalProps {
  companyId: string
  /** `null` creates a new vehicle; otherwise the form edits this one. */
  vehicle: VehicleView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof VehicleFormValues>([
  'code', 'licensePlate', 'carrierId', 'vehicleTypeId', 'maxWeightOverrideKg', 'maxVolumeOverrideM3',
  'maxPalletsOverride', 'availabilityStatus',
])

/**
 * Create and edit share one form; see `DestinationFormModal` (masters) for the same
 * "assigned value still shown after deactivation" pattern applied to `carrierId`/`vehicleTypeId`
 * here.
 */
export function VehicleFormModal({ companyId, vehicle, onClose, onSaved }: VehicleFormModalProps) {
  const isEdit = vehicle !== null
  const [formError, setFormError] = useState<string | null>(null)

  const carriersQuery = useQuery({
    queryKey: ['carriers-for-vehicle-form', companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const carriers = carriersQuery.data?.content ?? []

  const vehicleTypesQuery = useQuery({
    queryKey: ['vehicle-types-for-vehicle-form', companyId],
    queryFn: ({ signal }) => fetchVehicleTypes({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const vehicleTypes = vehicleTypesQuery.data?.content ?? []

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<VehicleFormValues>({
    defaultValues: {
      code: vehicle?.code ?? '',
      licensePlate: vehicle?.licensePlate ?? '',
      carrierId: vehicle?.carrierId ?? '',
      vehicleTypeId: vehicle?.vehicleTypeId ?? '',
      maxWeightOverrideKg: vehicle?.maxWeightOverrideKg?.toString() ?? '',
      maxVolumeOverrideM3: vehicle?.maxVolumeOverrideM3?.toString() ?? '',
      maxPalletsOverride: vehicle?.maxPalletsOverride?.toString() ?? '',
      availabilityStatus: vehicle?.availabilityStatus ?? 'AVAILABLE',
    },
  })

  const validatePositive: Validate<string, VehicleFormValues> = (value) => {
    if (value.trim() === '') return true
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return 'Must be a number'
    return parsed > 0 || 'Must be greater than zero'
  }

  async function onSubmit(values: VehicleFormValues) {
    setFormError(null)
    if (!values.vehicleTypeId) {
      setFormError('A vehicle type is required.')
      return
    }

    const request: VehicleRequest = {
      code: values.code.trim(),
      licensePlate: values.licensePlate.trim(),
      carrierId: values.carrierId || null,
      vehicleTypeId: values.vehicleTypeId,
      maxWeightOverrideKg: values.maxWeightOverrideKg.trim() === '' ? null : Number(values.maxWeightOverrideKg),
      maxVolumeOverrideM3: values.maxVolumeOverrideM3.trim() === '' ? null : Number(values.maxVolumeOverrideM3),
      maxPalletsOverride: values.maxPalletsOverride.trim() === '' ? null : Number(values.maxPalletsOverride),
      availabilityStatus: values.availabilityStatus,
    }

    try {
      if (isEdit) {
        await updateVehicle(companyId, vehicle.id, request)
      } else {
        await createVehicle(companyId, request)
      }
      onSaved()
    } catch (error) {
      const apiError = error as ApiError
      if (apiError.fieldErrors.length > 0) {
        const unmatched: string[] = []
        for (const fieldError of apiError.fieldErrors) {
          if (KNOWN_FIELDS.has(fieldError.field as keyof VehicleFormValues)) {
            setError(fieldError.field as keyof VehicleFormValues, { message: fieldError.message })
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
              <h5 className="modal-title">{isEdit ? 'Edit vehicle' : 'New vehicle'}</h5>
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
                  <FormField label="Code" htmlFor="vehicle-code" error={errors.code?.message} required>
                    <input
                      id="vehicle-code"
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
                <div className="col-md-3">
                  <FormField label="License plate" htmlFor="vehicle-license-plate" error={errors.licensePlate?.message} required>
                    <input
                      id="vehicle-license-plate"
                      className={`form-control${errors.licensePlate ? ' is-invalid' : ''}`}
                      {...register('licensePlate', {
                        required: 'License plate is required',
                        maxLength: { value: 12, message: 'Must be 12 characters or fewer' },
                        pattern: {
                          value: /^[A-Za-z0-9-]{4,12}$/,
                          message: '4-12 characters: letters, digits or hyphen',
                        },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Vehicle type" htmlFor="vehicle-type" error={errors.vehicleTypeId?.message} required>
                    <select
                      id="vehicle-type"
                      className={`form-select${errors.vehicleTypeId ? ' is-invalid' : ''}`}
                      {...register('vehicleTypeId', { required: 'Vehicle type is required' })}
                    >
                      <option value="">Select a type</option>
                      {vehicle?.vehicleTypeId && !vehicleTypes.some((type) => type.id === vehicle.vehicleTypeId) && (
                        <option value={vehicle.vehicleTypeId}>{vehicle.vehicleTypeCode ?? vehicle.vehicleTypeId}</option>
                      )}
                      {vehicleTypes.map((type) => (
                        <option key={type.id} value={type.id}>
                          {type.code} — {type.name}
                        </option>
                      ))}
                    </select>
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Carrier" htmlFor="vehicle-carrier" error={errors.carrierId?.message}>
                    <select id="vehicle-carrier" className="form-select" {...register('carrierId')}>
                      <option value="">No carrier (owned fleet)</option>
                      {vehicle?.carrierId && !carriers.some((carrier) => carrier.id === vehicle.carrierId) && (
                        <option value={vehicle.carrierId}>{vehicle.carrierCode ?? vehicle.carrierId}</option>
                      )}
                      {carriers.map((carrier) => (
                        <option key={carrier.id} value={carrier.id}>
                          {carrier.code} — {carrier.businessName}
                        </option>
                      ))}
                    </select>
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-3">
                  <FormField
                    label="Weight override (kg)"
                    htmlFor="vehicle-max-weight-override"
                    error={errors.maxWeightOverrideKg?.message}
                  >
                    <input
                      id="vehicle-max-weight-override"
                      type="text"
                      inputMode="decimal"
                      placeholder="Type default"
                      className={`form-control${errors.maxWeightOverrideKg ? ' is-invalid' : ''}`}
                      {...register('maxWeightOverrideKg', { validate: validatePositive })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField
                    label="Volume override (m³)"
                    htmlFor="vehicle-max-volume-override"
                    error={errors.maxVolumeOverrideM3?.message}
                  >
                    <input
                      id="vehicle-max-volume-override"
                      type="text"
                      inputMode="decimal"
                      placeholder="Type default"
                      className={`form-control${errors.maxVolumeOverrideM3 ? ' is-invalid' : ''}`}
                      {...register('maxVolumeOverrideM3', { validate: validatePositive })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField
                    label="Pallets override"
                    htmlFor="vehicle-max-pallets-override"
                    error={errors.maxPalletsOverride?.message}
                  >
                    <input
                      id="vehicle-max-pallets-override"
                      type="number"
                      min={0}
                      placeholder="Type default"
                      className={`form-control${errors.maxPalletsOverride ? ' is-invalid' : ''}`}
                      {...register('maxPalletsOverride', { min: { value: 0, message: 'Must be zero or greater' } })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Availability" htmlFor="vehicle-availability" error={errors.availabilityStatus?.message} required>
                    <select
                      id="vehicle-availability"
                      className="form-select"
                      {...register('availabilityStatus', { required: true })}
                    >
                      {VEHICLE_AVAILABILITY_STATUSES.map((status) => (
                        <option key={status} value={status}>
                          {VEHICLE_AVAILABILITY_STATUS_LABELS[status]}
                        </option>
                      ))}
                    </select>
                  </FormField>
                </div>
              </div>
              <p className="text-muted small mb-0">
                Leave an override blank to use the vehicle type's default for that dimension.
              </p>
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
