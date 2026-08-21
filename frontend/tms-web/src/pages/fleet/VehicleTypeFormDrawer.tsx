import { useState } from 'react'
import { Controller, useForm, type Validate } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import {
  createVehicleType,
  updateVehicleType,
  VEHICLE_BODY_TYPES,
  type VehicleBodyType,
  type VehicleTypeRequest,
  type VehicleTypeView,
} from '../../shared/api/vehicleTypesApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'vehicle-type-form'

/** Matches the backend's `code` constraint; kept next to the field it validates. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/

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

interface VehicleTypeFormDrawerProps {
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

/** Create and edit share one form. Fields are grouped as an operator specifies a unit class:
 * what it is, what it can carry, how big it is, and what it is restricted to. */
export function VehicleTypeFormDrawer({ companyId, vehicleType, onClose, onSaved }: VehicleTypeFormDrawerProps) {
  const { t } = useTranslation('fleet')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const enumLabels = useEnumLabels()
  const isEdit = vehicleType !== null
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
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
    if (Number.isNaN(parsed)) return tv('number')
    return parsed > 0 || tv('positiveNumber')
  }

  const validateRequiredPositive: Validate<string, VehicleTypeFormValues> = (value) => {
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    return parsed > 0 || tv('positiveNumber')
  }

  const validateTemperature: Validate<string, VehicleTypeFormValues> = (value, formValues) => {
    if (value.trim() === '') return true
    if (!formValues.temperatureControlled) return tv('temperatureOnlyWhenControlled')
    return true
  }

  async function onSubmit(values: VehicleTypeFormValues) {
    setFormError(null)
    if (values.minTemperatureCelsius.trim() !== '' && values.maxTemperatureCelsius.trim() !== ''
        && Number(values.minTemperatureCelsius) > Number(values.maxTemperatureCelsius)) {
      setFormError(t('vehicleTypes.form.temperatureOrder'))
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
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('vehicleTypes.form.edit') : t('vehicleTypes.form.create')}
      subtitle={t('vehicleTypes.form.subtitle')}
      size="lg"
      onClose={onClose}
      dirty={isDirty}
      closeOnEscape={!isSubmitting}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={isSubmitting}>
            {tc('actions.cancel')}
          </button>
          <button type="submit" form={FORM_ID} className="btn btn-primary" disabled={isSubmitting}>
            {isSubmitting ? tc('actions.saving') : tc('actions.save')}
          </button>
        </>
      }
    >
      <form id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && (
          <div className="alert alert-danger py-2 small" role="alert">
            {formError}
          </div>
        )}

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.identification')}</legend>
          <div className="row">
            <div className="col-12 col-sm-6">
              <FormField label={tc('columns.code')} htmlFor="vehicle-type-code" error={errors.code?.message} required>
                <input
                  id="vehicle-type-code"
                  className={`form-control${errors.code ? ' is-invalid' : ''}`}
                  {...register('code', {
                    required: tv('required'),
                    maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                    pattern: { value: CODE_PATTERN, message: tv('codePattern') },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-12">
              <FormField label={tc('columns.name')} htmlFor="vehicle-type-name" error={errors.name?.message} required>
                <input
                  id="vehicle-type-name"
                  className={`form-control${errors.name ? ' is-invalid' : ''}`}
                  {...register('name', {
                    required: tv('required'),
                    maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField label={tc('fields.bodyType')} htmlFor="vehicle-type-body-type" error={errors.bodyType?.message}>
                <Controller
                  control={control}
                  name="bodyType"
                  render={({ field }) => (
                    <Select
                      id="vehicle-type-body-type"
                      value={field.value}
                      onChange={(value) => field.onChange(value as VehicleBodyType | '')}
                      options={[
                        { value: '', label: t('vehicleTypes.form.noBodyType') },
                        ...VEHICLE_BODY_TYPES.map((type) => ({ value: type, label: enumLabels.vehicleBodyType(type) })),
                      ]}
                    />
                  )}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.capacities')}</legend>
          <div className="row">
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('columns.maxWeight')}
                htmlFor="vehicle-type-max-weight"
                error={errors.maxWeightKg?.message}
                required
              >
                <input
                  id="vehicle-type-max-weight"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.maxWeightKg ? ' is-invalid' : ''}`}
                  {...register('maxWeightKg', { required: tv('required'), validate: validateRequiredPositive })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('columns.maxVolume')}
                htmlFor="vehicle-type-max-volume"
                error={errors.maxVolumeM3?.message}
                required
              >
                <input
                  id="vehicle-type-max-volume"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.maxVolumeM3 ? ' is-invalid' : ''}`}
                  {...register('maxVolumeM3', { required: tv('required'), validate: validateRequiredPositive })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('columns.maxPallets')}
                htmlFor="vehicle-type-max-pallets"
                error={errors.maxPallets?.message}
                required
              >
                <input
                  id="vehicle-type-max-pallets"
                  type="number"
                  min={0}
                  className={`form-control${errors.maxPallets ? ' is-invalid' : ''}`}
                  {...register('maxPallets', {
                    required: tv('required'),
                    min: { value: 0, message: tv('nonNegative') },
                  })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.dimensions')}</legend>
          <div className="row">
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.lengthM')} htmlFor="vehicle-type-length" error={errors.lengthM?.message}>
                <input
                  id="vehicle-type-length"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.lengthM ? ' is-invalid' : ''}`}
                  {...register('lengthM', { validate: validatePositive })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.widthM')} htmlFor="vehicle-type-width" error={errors.widthM?.message}>
                <input
                  id="vehicle-type-width"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.widthM ? ' is-invalid' : ''}`}
                  {...register('widthM', { validate: validatePositive })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.heightM')} htmlFor="vehicle-type-height" error={errors.heightM?.message}>
                <input
                  id="vehicle-type-height"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.heightM ? ' is-invalid' : ''}`}
                  {...register('heightM', { validate: validatePositive })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.axles')} htmlFor="vehicle-type-axles" error={errors.axles?.message}>
                <input
                  id="vehicle-type-axles"
                  type="number"
                  min={1}
                  className={`form-control${errors.axles ? ' is-invalid' : ''}`}
                  {...register('axles', { min: { value: 1, message: tv('min', { min: 1 }) } })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{tc('sections.restrictions')}</legend>
          <div className="row align-items-start">
            <div className="col-12 col-sm-4">
              <div className="form-check mb-3">
                <input
                  id="vehicle-type-temperature-controlled"
                  type="checkbox"
                  className="form-check-input"
                  {...register('temperatureControlled')}
                />
                <label className="form-check-label" htmlFor="vehicle-type-temperature-controlled">
                  {t('vehicleTypes.form.temperatureControlled')}
                </label>
              </div>
            </div>
            <div className="col-6 col-sm-4">
              <FormField
                label={tc('fields.minTemperature')}
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
            <div className="col-6 col-sm-4">
              <FormField
                label={tc('fields.maxTemperature')}
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
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
