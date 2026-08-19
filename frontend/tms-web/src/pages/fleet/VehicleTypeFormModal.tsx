import { useState } from 'react'
import { useForm, type Validate } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import {
  createVehicleType,
  updateVehicleType,
  VEHICLE_BODY_TYPE_LABELS,
  VEHICLE_BODY_TYPES,
  type VehicleBodyType,
  type VehicleTypeRequest,
  type VehicleTypeView,
} from '../../shared/api/vehicleTypesApi'
import { FormField } from '../../shared/ui/components/FormField'

interface VehicleTypeFormValues {
  code: string
  name: string
  maxWeightKg: string
  maxVolumeM3: string
  maxPallets: string
  lengthM: string
  widthM: string
  heightM: string
  bodyType: VehicleBodyType | ''
  temperatureControlled: boolean
  minTemperatureCelsius: string
  maxTemperatureCelsius: string
  axles: string
}

interface VehicleTypeFormModalProps {
  companyId: string
  /** `null` creates a new vehicle type; otherwise the form edits this one. */
  vehicleType: VehicleTypeView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof VehicleTypeFormValues>([
  'code', 'name', 'maxWeightKg', 'maxVolumeM3', 'maxPallets', 'lengthM', 'widthM', 'heightM', 'bodyType',
  'temperatureControlled', 'minTemperatureCelsius', 'maxTemperatureCelsius', 'axles',
])

/** Create and edit share one form; see `DestinationFormModal` (masters) for the same multi-row layout pattern. */
export function VehicleTypeFormModal({ companyId, vehicleType, onClose, onSaved }: VehicleTypeFormModalProps) {
  const isEdit = vehicleType !== null
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<VehicleTypeFormValues>({
    defaultValues: {
      code: vehicleType?.code ?? '',
      name: vehicleType?.name ?? '',
      maxWeightKg: vehicleType?.maxWeightKg?.toString() ?? '',
      maxVolumeM3: vehicleType?.maxVolumeM3?.toString() ?? '',
      maxPallets: vehicleType?.maxPallets?.toString() ?? '0',
      lengthM: vehicleType?.lengthM?.toString() ?? '',
      widthM: vehicleType?.widthM?.toString() ?? '',
      heightM: vehicleType?.heightM?.toString() ?? '',
      bodyType: vehicleType?.bodyType ?? '',
      temperatureControlled: vehicleType?.temperatureControlled ?? false,
      minTemperatureCelsius: vehicleType?.minTemperatureCelsius?.toString() ?? '',
      maxTemperatureCelsius: vehicleType?.maxTemperatureCelsius?.toString() ?? '',
      axles: vehicleType?.axles?.toString() ?? '',
    },
  })

  const validatePositive: Validate<string, VehicleTypeFormValues> = (value) => {
    if (value.trim() === '') return true
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return 'Must be a number'
    return parsed > 0 || 'Must be greater than zero'
  }

  const validateTemperature: Validate<string, VehicleTypeFormValues> = (value, formValues) => {
    if (value.trim() === '') return true
    if (!formValues.temperatureControlled) return 'Only allowed when temperature controlled is checked'
    return true
  }

  async function onSubmit(values: VehicleTypeFormValues) {
    setFormError(null)
    if (values.minTemperatureCelsius.trim() !== '' && values.maxTemperatureCelsius.trim() !== ''
        && Number(values.minTemperatureCelsius) > Number(values.maxTemperatureCelsius)) {
      setFormError('Minimum temperature must not be greater than maximum temperature.')
      return
    }

    const request: VehicleTypeRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      maxWeightKg: Number(values.maxWeightKg),
      maxVolumeM3: Number(values.maxVolumeM3),
      maxPallets: Number(values.maxPallets),
      lengthM: values.lengthM.trim() === '' ? null : Number(values.lengthM),
      widthM: values.widthM.trim() === '' ? null : Number(values.widthM),
      heightM: values.heightM.trim() === '' ? null : Number(values.heightM),
      bodyType: values.bodyType || null,
      temperatureControlled: values.temperatureControlled,
      minTemperatureCelsius: values.minTemperatureCelsius.trim() === '' ? null : Number(values.minTemperatureCelsius),
      maxTemperatureCelsius: values.maxTemperatureCelsius.trim() === '' ? null : Number(values.maxTemperatureCelsius),
      axles: values.axles.trim() === '' ? null : Number(values.axles),
    }

    try {
      if (isEdit) {
        await updateVehicleType(companyId, vehicleType.id, request)
      } else {
        await createVehicleType(companyId, request)
      }
      onSaved()
    } catch (error) {
      const apiError = error as ApiError
      if (apiError.fieldErrors.length > 0) {
        const unmatched: string[] = []
        for (const fieldError of apiError.fieldErrors) {
          if (KNOWN_FIELDS.has(fieldError.field as keyof VehicleTypeFormValues)) {
            setError(fieldError.field as keyof VehicleTypeFormValues, { message: fieldError.message })
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
              <h5 className="modal-title">{isEdit ? 'Edit vehicle type' : 'New vehicle type'}</h5>
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
                  <FormField label="Code" htmlFor="vehicle-type-code" error={errors.code?.message} required>
                    <input
                      id="vehicle-type-code"
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
                  <FormField label="Name" htmlFor="vehicle-type-name" error={errors.name?.message} required>
                    <input
                      id="vehicle-type-name"
                      className={`form-control${errors.name ? ' is-invalid' : ''}`}
                      {...register('name', {
                        required: 'Name is required',
                        maxLength: { value: 200, message: 'Must be 200 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Body type" htmlFor="vehicle-type-body-type" error={errors.bodyType?.message}>
                    <select id="vehicle-type-body-type" className="form-select" {...register('bodyType')}>
                      <option value="">None</option>
                      {VEHICLE_BODY_TYPES.map((type) => (
                        <option key={type} value={type}>
                          {VEHICLE_BODY_TYPE_LABELS[type]}
                        </option>
                      ))}
                    </select>
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-4">
                  <FormField label="Max weight (kg)" htmlFor="vehicle-type-max-weight" error={errors.maxWeightKg?.message} required>
                    <input
                      id="vehicle-type-max-weight"
                      type="text"
                      inputMode="decimal"
                      className={`form-control${errors.maxWeightKg ? ' is-invalid' : ''}`}
                      {...register('maxWeightKg', {
                        required: 'Max weight is required',
                        validate: (value) => {
                          const parsed = Number(value)
                          if (Number.isNaN(parsed)) return 'Must be a number'
                          return parsed > 0 || 'Must be greater than zero'
                        },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-md-4">
                  <FormField label="Max volume (m³)" htmlFor="vehicle-type-max-volume" error={errors.maxVolumeM3?.message} required>
                    <input
                      id="vehicle-type-max-volume"
                      type="text"
                      inputMode="decimal"
                      className={`form-control${errors.maxVolumeM3 ? ' is-invalid' : ''}`}
                      {...register('maxVolumeM3', {
                        required: 'Max volume is required',
                        validate: (value) => {
                          const parsed = Number(value)
                          if (Number.isNaN(parsed)) return 'Must be a number'
                          return parsed > 0 || 'Must be greater than zero'
                        },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-md-4">
                  <FormField label="Max pallets" htmlFor="vehicle-type-max-pallets" error={errors.maxPallets?.message} required>
                    <input
                      id="vehicle-type-max-pallets"
                      type="number"
                      min={0}
                      className={`form-control${errors.maxPallets ? ' is-invalid' : ''}`}
                      {...register('maxPallets', {
                        required: 'Max pallets is required',
                        min: { value: 0, message: 'Must be zero or greater' },
                      })}
                    />
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-3">
                  <FormField label="Length (m)" htmlFor="vehicle-type-length" error={errors.lengthM?.message}>
                    <input
                      id="vehicle-type-length"
                      type="text"
                      inputMode="decimal"
                      className={`form-control${errors.lengthM ? ' is-invalid' : ''}`}
                      {...register('lengthM', { validate: validatePositive })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Width (m)" htmlFor="vehicle-type-width" error={errors.widthM?.message}>
                    <input
                      id="vehicle-type-width"
                      type="text"
                      inputMode="decimal"
                      className={`form-control${errors.widthM ? ' is-invalid' : ''}`}
                      {...register('widthM', { validate: validatePositive })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Height (m)" htmlFor="vehicle-type-height" error={errors.heightM?.message}>
                    <input
                      id="vehicle-type-height"
                      type="text"
                      inputMode="decimal"
                      className={`form-control${errors.heightM ? ' is-invalid' : ''}`}
                      {...register('heightM', { validate: validatePositive })}
                    />
                  </FormField>
                </div>
                <div className="col-md-3">
                  <FormField label="Axles" htmlFor="vehicle-type-axles" error={errors.axles?.message}>
                    <input
                      id="vehicle-type-axles"
                      type="number"
                      min={1}
                      className={`form-control${errors.axles ? ' is-invalid' : ''}`}
                      {...register('axles', { min: { value: 1, message: 'Must be at least 1' } })}
                    />
                  </FormField>
                </div>
              </div>
              <div className="row align-items-end">
                <div className="col-md-3">
                  <div className="form-check mb-3">
                    <input
                      id="vehicle-type-temperature-controlled"
                      type="checkbox"
                      className="form-check-input"
                      {...register('temperatureControlled')}
                    />
                    <label className="form-check-label" htmlFor="vehicle-type-temperature-controlled">
                      Temperature controlled
                    </label>
                  </div>
                </div>
                <div className="col-md-4">
                  <FormField
                    label="Min temperature (°C)"
                    htmlFor="vehicle-type-min-temperature"
                    error={errors.minTemperatureCelsius?.message}
                  >
                    <input
                      id="vehicle-type-min-temperature"
                      type="text"
                      inputMode="decimal"
                      className={`form-control${errors.minTemperatureCelsius ? ' is-invalid' : ''}`}
                      {...register('minTemperatureCelsius', { validate: validateTemperature })}
                    />
                  </FormField>
                </div>
                <div className="col-md-4">
                  <FormField
                    label="Max temperature (°C)"
                    htmlFor="vehicle-type-max-temperature"
                    error={errors.maxTemperatureCelsius?.message}
                  >
                    <input
                      id="vehicle-type-max-temperature"
                      type="text"
                      inputMode="decimal"
                      className={`form-control${errors.maxTemperatureCelsius ? ' is-invalid' : ''}`}
                      {...register('maxTemperatureCelsius', { validate: validateTemperature })}
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
