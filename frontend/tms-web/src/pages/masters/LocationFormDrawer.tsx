import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useForm, type Validate } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import {
  createLocation,
  LOCATION_ROLES,
  LOCATION_TYPES,
  updateLocation,
  type LocationRequest,
  type LocationRole,
  type LocationType,
  type LocationView,
} from '../../shared/api/locationsApi'
import { fetchZones } from '../../shared/api/zonesApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'location-form'

/** Matches the backend's `code` constraint; kept next to the field it validates. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/

// Intl.supportedValuesOf is widely available in evergreen browsers; an empty list just means the
// time zone field falls back to plain free text with the validate() check below - same as
// OriginFormDrawer.
const TIME_ZONES: string[] = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : []

function isValidTimeZone(value: string): boolean {
  try {
    Intl.DateTimeFormat(undefined, { timeZone: value })
    return true
  } catch {
    return false
  }
}

interface LocationFormValues {
  code: string
  name: string
  type: LocationType
  roles: LocationRole[]
  zoneId: string
  address: string
  addressReference: string
  district: string
  province: string
  department: string
  country: string
  timeZone: string
  latitude: string
  longitude: string
  serviceTimeMinutes: string
  externalSystem: string
  externalReference: string
}

interface LocationFormDrawerProps {
  companyId: string
  /** `null` creates a new location; otherwise the form edits this one. */
  location: LocationView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof LocationFormValues>([
  'code', 'name', 'type', 'roles', 'zoneId', 'address', 'addressReference', 'district', 'province',
  'department', 'country', 'timeZone', 'latitude', 'longitude', 'serviceTimeMinutes',
  'externalSystem', 'externalReference',
])

/**
 * Create and edit share one form: the fields and validation are identical (see
 * `LocationRequest`). Seventeen fields is far too many for one flat list, so they are grouped
 * the way an operator thinks about a place: what it is, what it may do, where it is, how it is
 * served, and how another system refers to it.
 *
 * Roles are checkboxes rather than a multi-select, because the two that matter carry a
 * consequence a closed list would hide: ticking ORIGIN or SHIP_TO is what makes the location
 * usable in orders, routes and planning at all.
 */
export function LocationFormDrawer({ companyId, location, onClose, onSaved }: LocationFormDrawerProps) {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const enumLabels = useEnumLabels()
  const isEdit = location !== null
  const [formError, setFormError] = useState<string | null>(null)

  const zonesQuery = useQuery({
    queryKey: ['zones-for-location-form', companyId],
    queryFn: ({ signal }) => fetchZones({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const zones = zonesQuery.data?.content ?? []

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<LocationFormValues>({
    defaultValues: {
      code: location?.code ?? '',
      name: location?.name ?? '',
      type: location?.type ?? 'STORE',
      roles: location?.roles ?? ['SHIP_TO'],
      zoneId: location?.zoneId ?? '',
      address: location?.address ?? '',
      addressReference: location?.addressReference ?? '',
      district: location?.district ?? '',
      province: location?.province ?? '',
      department: location?.department ?? '',
      country: location?.country ?? 'PE',
      timeZone: location?.timeZone ?? 'America/Lima',
      latitude: location?.latitude?.toString() ?? '',
      longitude: location?.longitude?.toString() ?? '',
      serviceTimeMinutes: location?.serviceTimeMinutes?.toString() ?? '0',
      externalSystem: location?.externalSystem ?? '',
      externalReference: location?.externalReference ?? '',
    },
  })

  const validateLatitude: Validate<string, LocationFormValues> = (value, formValues) => {
    if (value.trim() === '') {
      return formValues.longitude.trim() === '' || tv('latLongPair')
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    if (parsed < -90 || parsed > 90) return tv('range', { min: -90, max: 90 })
    return true
  }

  const validateLongitude: Validate<string, LocationFormValues> = (value, formValues) => {
    if (value.trim() === '') {
      return formValues.latitude.trim() === '' || tv('latLongPair')
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    if (parsed < -180 || parsed > 180) return tv('range', { min: -180, max: 180 })
    return true
  }

  /** Mirrors `ck_location_external_pair_complete`: a reference with no system deduplicates nothing. */
  const validateExternalPair: Validate<string, LocationFormValues> = (_value, formValues) =>
    (formValues.externalSystem.trim() === '') === (formValues.externalReference.trim() === '')
    || tv('externalIdentityPair')

  async function onSubmit(values: LocationFormValues) {
    setFormError(null)
    const request: LocationRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      type: values.type,
      roles: values.roles,
      zoneId: values.zoneId || null,
      address: values.address.trim() || null,
      addressReference: values.addressReference.trim() || null,
      district: values.district.trim() || null,
      province: values.province.trim() || null,
      department: values.department.trim() || null,
      country: values.country.trim(),
      timeZone: values.timeZone.trim(),
      latitude: values.latitude.trim() === '' ? null : Number(values.latitude),
      longitude: values.longitude.trim() === '' ? null : Number(values.longitude),
      serviceTimeMinutes: values.serviceTimeMinutes.trim() === '' ? 0 : Number(values.serviceTimeMinutes),
      externalSystem: values.externalSystem.trim() || null,
      externalReference: values.externalReference.trim() || null,
    }

    try {
      if (isEdit) {
        await updateLocation(companyId, location.id, request)
      } else {
        await createLocation(companyId, request)
      }
      onSaved()
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('locations.form.edit') : t('locations.form.create')}
      subtitle={t('locations.form.subtitle')}
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
              <FormField label={tc('columns.code')} htmlFor="location-code" error={errors.code?.message} required>
                <input
                  id="location-code"
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
              <FormField label={tc('columns.name')} htmlFor="location-name" error={errors.name?.message} required>
                <input
                  id="location-name"
                  className={`form-control${errors.name ? ' is-invalid' : ''}`}
                  {...register('name', {
                    required: tv('required'),
                    maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField label={tc('columns.type')} htmlFor="location-type" error={errors.type?.message} required>
                {/* Controller rather than `register`: the control is a button plus a listbox, so
                    there is no native change event for react-hook-form to hook into. */}
                <Controller
                  control={control}
                  name="type"
                  rules={{ required: true }}
                  render={({ field }) => (
                    <Select
                      id="location-type"
                      value={field.value}
                      onChange={(next) => field.onChange(next as LocationType)}
                      invalid={Boolean(errors.type)}
                      options={LOCATION_TYPES.map((type) => ({
                        value: type,
                        label: enumLabels.locationType(type),
                      }))}
                    />
                  )}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField label={tc('columns.zone')} htmlFor="location-zone" error={errors.zoneId?.message}>
                <Controller
                  control={control}
                  name="zoneId"
                  render={({ field }) => (
                    <Select
                      id="location-zone"
                      value={field.value}
                      onChange={field.onChange}
                      options={[
                        { value: '', label: t('locations.form.noZone') },
                        ...zones.map((zone) => ({ value: zone.id, label: zone.name })),
                      ]}
                    />
                  )}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{t('locations.form.sectionRoles')}</legend>
          {/* FormField's htmlFor points at the group div, not at the first checkbox. A
              `<label for>` aimed at a non-labelable element contributes nothing to any control's
              accessible name, which is what keeps each box named "Origen", "Destino", ...
              rather than "Roles Origen". */}
          <Controller
            control={control}
            name="roles"
            rules={{ validate: (value) => value.length > 0 || tv('atLeastOneRole') }}
            render={({ field }) => (
              <FormField
                label={t('locations.columns.roles')}
                htmlFor="location-roles"
                error={errors.roles?.message}
                help={t('locations.form.rolesHelp')}
                required
              >
                <div
                  id="location-roles"
                  className="d-flex flex-wrap gap-3"
                  role="group"
                  aria-label={t('locations.columns.roles')}
                >
                  {LOCATION_ROLES.map((role) => (
                    <div className="form-check" key={role}>
                      <input
                        className="form-check-input"
                        type="checkbox"
                        id={`location-role-${role}`}
                        checked={field.value.includes(role)}
                        onChange={(event) =>
                          field.onChange(
                            event.target.checked
                              ? [...field.value, role]
                              : field.value.filter((held) => held !== role),
                          )
                        }
                      />
                      <label className="form-check-label" htmlFor={`location-role-${role}`}>
                        {enumLabels.locationRole(role)}
                      </label>
                    </div>
                  ))}
                </div>
              </FormField>
            )}
          />
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.address')}</legend>
          <div className="row">
            <div className="col-12 col-sm-6">
              <FormField label={tc('columns.address')} htmlFor="location-address" error={errors.address?.message}>
                <input
                  id="location-address"
                  className={`form-control${errors.address ? ' is-invalid' : ''}`}
                  {...register('address', { maxLength: { value: 500, message: tv('maxLength', { count: 500 }) } })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('fields.reference')}
                htmlFor="location-address-reference"
                error={errors.addressReference?.message}
              >
                <input
                  id="location-address-reference"
                  placeholder={tc('placeholders.addressReference')}
                  className={`form-control${errors.addressReference ? ' is-invalid' : ''}`}
                  {...register('addressReference', {
                    maxLength: { value: 300, message: tv('maxLength', { count: 300 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.district')} htmlFor="location-district" error={errors.district?.message}>
                <input
                  id="location-district"
                  className={`form-control${errors.district ? ' is-invalid' : ''}`}
                  {...register('district', { maxLength: { value: 120, message: tv('maxLength', { count: 120 }) } })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.province')} htmlFor="location-province" error={errors.province?.message}>
                <input
                  id="location-province"
                  className={`form-control${errors.province ? ' is-invalid' : ''}`}
                  {...register('province', { maxLength: { value: 120, message: tv('maxLength', { count: 120 }) } })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField
                label={tc('fields.department')}
                htmlFor="location-department"
                error={errors.department?.message}
              >
                <input
                  id="location-department"
                  className={`form-control${errors.department ? ' is-invalid' : ''}`}
                  {...register('department', { maxLength: { value: 120, message: tv('maxLength', { count: 120 }) } })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField
                label={tc('fields.country')}
                htmlFor="location-country"
                error={errors.country?.message}
                required
              >
                <input
                  id="location-country"
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
              <FormField label={tc('fields.latitude')} htmlFor="location-latitude" error={errors.latitude?.message}>
                <input
                  id="location-latitude"
                  type="text"
                  inputMode="decimal"
                  placeholder={tc('placeholders.latitude')}
                  className={`form-control${errors.latitude ? ' is-invalid' : ''}`}
                  {...register('latitude', { validate: validateLatitude })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.longitude')} htmlFor="location-longitude" error={errors.longitude?.message}>
                <input
                  id="location-longitude"
                  type="text"
                  inputMode="decimal"
                  placeholder={tc('placeholders.longitude')}
                  className={`form-control${errors.longitude ? ' is-invalid' : ''}`}
                  {...register('longitude', { validate: validateLongitude })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('columns.timeZone')}
                htmlFor="location-timezone"
                error={errors.timeZone?.message}
                required
              >
                <input
                  id="location-timezone"
                  list="tms-time-zones"
                  placeholder={tc('placeholders.timeZone')}
                  className={`form-control${errors.timeZone ? ' is-invalid' : ''}`}
                  {...register('timeZone', {
                    required: tv('required'),
                    validate: (value) => isValidTimeZone(value.trim()) || tv('timeZone'),
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
        </fieldset>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{tc('sections.operation')}</legend>
          <div className="row">
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('fields.serviceTimeMinutes')}
                htmlFor="location-service-time"
                error={errors.serviceTimeMinutes?.message}
                required
              >
                <input
                  id="location-service-time"
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
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('fields.externalSystem')}
                htmlFor="location-external-system"
                error={errors.externalSystem?.message}
                help={t('locations.form.identityHelp')}
              >
                <input
                  id="location-external-system"
                  placeholder={tc('placeholders.externalSystem')}
                  className={`form-control${errors.externalSystem ? ' is-invalid' : ''}`}
                  {...register('externalSystem', {
                    maxLength: { value: 60, message: tv('maxLength', { count: 60 }) },
                    validate: validateExternalPair,
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('fields.externalReference')}
                htmlFor="location-external-reference"
                error={errors.externalReference?.message}
              >
                <input
                  id="location-external-reference"
                  placeholder={tc('placeholders.externalReference')}
                  className={`form-control${errors.externalReference ? ' is-invalid' : ''}`}
                  {...register('externalReference', {
                    maxLength: { value: 100, message: tv('maxLength', { count: 100 }) },
                    validate: validateExternalPair,
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
