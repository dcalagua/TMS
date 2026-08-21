import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useForm, type Validate } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchCarriers } from '../../shared/api/carriersApi'
import { fetchVehicleTypes } from '../../shared/api/vehicleTypesApi'
import {
  createVehicle,
  updateVehicle,
  VEHICLE_AVAILABILITY_STATUSES,
  type VehicleAvailabilityStatus,
  type VehicleRequest,
  type VehicleView,
} from '../../shared/api/vehiclesApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'vehicle-form'

/** Matches the backend's constraints; kept next to the fields they validate. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/
const PLATE_PATTERN = /^[A-Za-z0-9-]{4,12}$/

interface VehicleFormValues {
  code: string
  licensePlate: string
  carrierId: string
  vehicleTypeId: string
  maxWeightOverrideKg: string
  maxVolumeOverrideM3: string
  maxPalletsOverride: string
  availabilityStatus: VehicleAvailabilityStatus
  externalReference: string
}

interface VehicleFormDrawerProps {
  companyId: string
  /** `null` creates a new vehicle; otherwise the form edits this one. */
  vehicle: VehicleView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof VehicleFormValues>([
  'code', 'licensePlate', 'carrierId', 'vehicleTypeId', 'maxWeightOverrideKg', 'maxVolumeOverrideM3',
  'maxPalletsOverride', 'availabilityStatus', 'externalReference',
])

/**
 * Create and edit share one form; see `DestinationFormDrawer` (masters) for the same
 * "assigned value still shown after deactivation" pattern applied to `carrierId`/`vehicleTypeId`
 * here.
 */
export function VehicleFormDrawer({ companyId, vehicle, onClose, onSaved }: VehicleFormDrawerProps) {
  const { t } = useTranslation('fleet')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const enumLabels = useEnumLabels()
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
    control,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
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
      externalReference: vehicle?.externalReference ?? '',
    },
  })

  const validatePositive: Validate<string, VehicleFormValues> = (value) => {
    if (value.trim() === '') return true
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    return parsed > 0 || tv('positiveNumber')
  }

  async function onSubmit(values: VehicleFormValues) {
    setFormError(null)
    if (!values.vehicleTypeId) {
      setError('vehicleTypeId', { message: tv('required') })
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
      externalReference: values.externalReference.trim() || null,
    }

    try {
      if (isEdit) {
        await updateVehicle(companyId, vehicle.id, request)
      } else {
        await createVehicle(companyId, request)
      }
      onSaved()
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('vehicles.form.edit') : t('vehicles.form.create')}
      subtitle={t('vehicles.form.subtitle')}
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
              <FormField label={tc('columns.code')} htmlFor="vehicle-code" error={errors.code?.message} required>
                <input
                  id="vehicle-code"
                  className={`form-control${errors.code ? ' is-invalid' : ''}`}
                  {...register('code', {
                    required: tv('required'),
                    maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                    pattern: { value: CODE_PATTERN, message: tv('codePattern') },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('fields.licensePlate')}
                htmlFor="vehicle-license-plate"
                error={errors.licensePlate?.message}
                required
              >
                <input
                  id="vehicle-license-plate"
                  className={`form-control${errors.licensePlate ? ' is-invalid' : ''}`}
                  {...register('licensePlate', {
                    required: tv('required'),
                    maxLength: { value: 12, message: tv('maxLength', { count: 12 }) },
                    pattern: { value: PLATE_PATTERN, message: tv('platePattern') },
                  })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.assignment')}</legend>
          <div className="row">
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('fields.vehicleType')}
                htmlFor="vehicle-type"
                error={errors.vehicleTypeId?.message}
                required
              >
                <Controller
                  control={control}
                  name="vehicleTypeId"
                  rules={{ required: tv('required') }}
                  render={({ field }) => (
                    <Select
                      id="vehicle-type"
                      value={field.value}
                      onChange={field.onChange}
                      invalid={Boolean(errors.vehicleTypeId)}
                      options={[
                        { value: '', label: t('vehicles.form.selectType') },
                        ...(vehicle?.vehicleTypeId && !vehicleTypes.some((type) => type.id === vehicle.vehicleTypeId)
                          ? [{ value: vehicle.vehicleTypeId, label: vehicle.vehicleTypeCode ?? vehicle.vehicleTypeId }]
                          : []),
                        ...vehicleTypes.map((type) => ({ value: type.id, label: `${type.code} — ${type.name}` })),
                      ]}
                    />
                  )}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField label={tc('columns.carrier')} htmlFor="vehicle-carrier" error={errors.carrierId?.message}>
                <Controller
                  control={control}
                  name="carrierId"
                  render={({ field }) => (
                    <Select
                      id="vehicle-carrier"
                      value={field.value}
                      onChange={field.onChange}
                      options={[
                        { value: '', label: t('vehicles.form.noCarrier') },
                        ...(vehicle?.carrierId && !carriers.some((carrier) => carrier.id === vehicle.carrierId)
                          ? [{ value: vehicle.carrierId, label: vehicle.carrierCode ?? vehicle.carrierId }]
                          : []),
                        ...carriers.map((carrier) => ({
                          value: carrier.id,
                          label: `${carrier.code} — ${carrier.businessName}`,
                        })),
                      ]}
                    />
                  )}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('fields.availability')}
                htmlFor="vehicle-availability"
                error={errors.availabilityStatus?.message}
                required
              >
                <Controller
                  control={control}
                  name="availabilityStatus"
                  rules={{ required: true }}
                  render={({ field }) => (
                    <Select
                      id="vehicle-availability"
                      value={field.value}
                      onChange={(value) => field.onChange(value as VehicleAvailabilityStatus)}
                      options={VEHICLE_AVAILABILITY_STATUSES.map((status) => ({
                        value: status,
                        label: enumLabels.vehicleAvailability(status),
                      }))}
                    />
                  )}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('fields.externalReference')}
                htmlFor="vehicle-external-reference"
                error={errors.externalReference?.message}
              >
                <input
                  id="vehicle-external-reference"
                  placeholder={tc('placeholders.externalReference')}
                  className={`form-control${errors.externalReference ? ' is-invalid' : ''}`}
                  {...register('externalReference', {
                    maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                  })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{tc('sections.capacities')}</legend>
          <div className="row">
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('fields.weightOverride')}
                htmlFor="vehicle-max-weight-override"
                error={errors.maxWeightOverrideKg?.message}
              >
                <input
                  id="vehicle-max-weight-override"
                  type="text"
                  inputMode="decimal"
                  placeholder={tc('placeholders.typeDefault')}
                  className={`form-control${errors.maxWeightOverrideKg ? ' is-invalid' : ''}`}
                  {...register('maxWeightOverrideKg', { validate: validatePositive })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('fields.volumeOverride')}
                htmlFor="vehicle-max-volume-override"
                error={errors.maxVolumeOverrideM3?.message}
              >
                <input
                  id="vehicle-max-volume-override"
                  type="text"
                  inputMode="decimal"
                  placeholder={tc('placeholders.typeDefault')}
                  className={`form-control${errors.maxVolumeOverrideM3 ? ' is-invalid' : ''}`}
                  {...register('maxVolumeOverrideM3', { validate: validatePositive })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('fields.palletsOverride')}
                htmlFor="vehicle-max-pallets-override"
                error={errors.maxPalletsOverride?.message}
              >
                <input
                  id="vehicle-max-pallets-override"
                  type="number"
                  min={0}
                  placeholder={tc('placeholders.typeDefault')}
                  className={`form-control${errors.maxPalletsOverride ? ' is-invalid' : ''}`}
                  {...register('maxPalletsOverride', { min: { value: 0, message: tv('nonNegative') } })}
                />
              </FormField>
            </div>
          </div>
          <p className="text-body-secondary small mb-0">{t('vehicles.form.overrideHelp')}</p>
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
