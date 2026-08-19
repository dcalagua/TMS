import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, type Validate } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import {
  createDestination,
  DESTINATION_TYPES,
  updateDestination,
  type DestinationRequest,
  type DestinationType,
  type DestinationView,
} from '../../shared/api/destinationsApi'
import { fetchZones } from '../../shared/api/zonesApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'destination-form'

/** Matches the backend's `code` constraint; kept next to the field it validates. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/

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

interface DestinationFormDrawerProps {
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

/** Create and edit share one form: the fields and validation are identical (see
 * `DestinationRequest`). Fourteen fields is too many for one flat list, so they are grouped
 * the way a planner reads a delivery point: what it is, where it is, and how it is served. */
export function DestinationFormDrawer({ companyId, destination, onClose, onSaved }: DestinationFormDrawerProps) {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const enumLabels = useEnumLabels()
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
    formState: { errors, isDirty, isSubmitting },
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
      return formValues.longitude.trim() === '' || tv('latLongPair')
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    if (parsed < -90 || parsed > 90) return tv('range', { min: -90, max: 90 })
    return true
  }

  const validateLongitude: Validate<string, DestinationFormValues> = (value, formValues) => {
    if (value.trim() === '') {
      return formValues.latitude.trim() === '' || tv('latLongPair')
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    if (parsed < -180 || parsed > 180) return tv('range', { min: -180, max: 180 })
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
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('destinations.form.edit') : t('destinations.form.create')}
      subtitle={t('destinations.form.subtitle')}
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
              <FormField label={tc('columns.code')} htmlFor="destination-code" error={errors.code?.message} required>
                <input
                  id="destination-code"
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
              <FormField label={tc('columns.name')} htmlFor="destination-name" error={errors.name?.message} required>
                <input
                  id="destination-name"
                  className={`form-control${errors.name ? ' is-invalid' : ''}`}
                  {...register('name', {
                    required: tv('required'),
                    maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-2">
              <FormField label={tc('columns.type')} htmlFor="destination-type" error={errors.type?.message} required>
                <select id="destination-type" className="form-select" {...register('type', { required: true })}>
                  {DESTINATION_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {enumLabels.destinationType(type)}
                    </option>
                  ))}
                </select>
              </FormField>
            </div>
            <div className="col-6 col-sm-2">
              <FormField label={tc('columns.zone')} htmlFor="destination-zone" error={errors.zoneId?.message}>
                <select id="destination-zone" className="form-select" {...register('zoneId')}>
                  <option value="">{t('destinations.form.noZone')}</option>
                  {zones.map((zone) => (
                    <option key={zone.id} value={zone.id}>
                      {zone.name}
                    </option>
                  ))}
                </select>
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.address')}</legend>
          <div className="row">
            <div className="col-12 col-sm-6">
              <FormField label={tc('columns.address')} htmlFor="destination-address" error={errors.address?.message}>
                <input
                  id="destination-address"
                  className={`form-control${errors.address ? ' is-invalid' : ''}`}
                  {...register('address', { maxLength: { value: 500, message: tv('maxLength', { count: 500 }) } })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('fields.reference')}
                htmlFor="destination-address-reference"
                error={errors.addressReference?.message}
              >
                <input
                  id="destination-address-reference"
                  placeholder={tc('placeholders.addressReference')}
                  className={`form-control${errors.addressReference ? ' is-invalid' : ''}`}
                  {...register('addressReference', {
                    maxLength: { value: 300, message: tv('maxLength', { count: 300 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.district')} htmlFor="destination-district" error={errors.district?.message}>
                <input
                  id="destination-district"
                  className={`form-control${errors.district ? ' is-invalid' : ''}`}
                  {...register('district', { maxLength: { value: 120, message: tv('maxLength', { count: 120 }) } })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.province')} htmlFor="destination-province" error={errors.province?.message}>
                <input
                  id="destination-province"
                  className={`form-control${errors.province ? ' is-invalid' : ''}`}
                  {...register('province', { maxLength: { value: 120, message: tv('maxLength', { count: 120 }) } })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField
                label={tc('fields.department')}
                htmlFor="destination-department"
                error={errors.department?.message}
              >
                <input
                  id="destination-department"
                  className={`form-control${errors.department ? ' is-invalid' : ''}`}
                  {...register('department', { maxLength: { value: 120, message: tv('maxLength', { count: 120 }) } })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.country')} htmlFor="destination-country" error={errors.country?.message} required>
                <input
                  id="destination-country"
                  className={`form-control${errors.country ? ' is-invalid' : ''}`}
                  {...register('country', {
                    required: tv('required'),
                    maxLength: { value: 60, message: tv('maxLength', { count: 60 }) },
                  })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.location')}</legend>
          <div className="row">
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.latitude')} htmlFor="destination-latitude" error={errors.latitude?.message}>
                <input
                  id="destination-latitude"
                  type="text"
                  inputMode="decimal"
                  placeholder={tc('placeholders.latitude')}
                  className={`form-control${errors.latitude ? ' is-invalid' : ''}`}
                  {...register('latitude', { validate: validateLatitude })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.longitude')} htmlFor="destination-longitude" error={errors.longitude?.message}>
                <input
                  id="destination-longitude"
                  type="text"
                  inputMode="decimal"
                  placeholder={tc('placeholders.longitude')}
                  className={`form-control${errors.longitude ? ' is-invalid' : ''}`}
                  {...register('longitude', { validate: validateLongitude })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{tc('sections.operation')}</legend>
          <div className="row">
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('fields.serviceTimeMinutes')}
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
                    required: tv('required'),
                    min: { value: 0, message: tv('nonNegative') },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('fields.externalReference')}
                htmlFor="destination-external-reference"
                error={errors.externalReference?.message}
              >
                <input
                  id="destination-external-reference"
                  placeholder={tc('placeholders.externalReference')}
                  className={`form-control${errors.externalReference ? ' is-invalid' : ''}`}
                  {...register('externalReference', {
                    maxLength: { value: 100, message: tv('maxLength', { count: 100 }) },
                  })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
