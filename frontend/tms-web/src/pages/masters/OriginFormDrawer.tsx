import { useState } from 'react'
import { useForm, type Validate } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import {
  createOrigin,
  ORIGIN_TYPES,
  updateOrigin,
  type OriginRequest,
  type OriginType,
  type OriginView,
} from '../../shared/api/originsApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'origin-form'

/** Matches the backend's `code` constraint; kept next to the field it validates. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/

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

interface OriginFormDrawerProps {
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

/** Create and edit share one form: the fields and validation are identical (see `OriginRequest`).
 * Fields are grouped the way an operator thinks about them - what the origin *is*, where it is,
 * and how it links to other systems - rather than in the order the request object declares. */
export function OriginFormDrawer({ companyId, origin, onClose, onSaved }: OriginFormDrawerProps) {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const enumLabels = useEnumLabels()
  const isEdit = origin !== null
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
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
      return formValues.longitude.trim() === '' || tv('latLongPair')
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    if (parsed < -90 || parsed > 90) return tv('range', { min: -90, max: 90 })
    return true
  }

  const validateLongitude: Validate<string, OriginFormValues> = (value, formValues) => {
    if (value.trim() === '') {
      return formValues.latitude.trim() === '' || tv('latLongPair')
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    if (parsed < -180 || parsed > 180) return tv('range', { min: -180, max: 180 })
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
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('origins.form.edit') : t('origins.form.create')}
      subtitle={t('origins.form.subtitle')}
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
              <FormField label={tc('columns.code')} htmlFor="origin-code" error={errors.code?.message} required>
                <input
                  id="origin-code"
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
              <FormField label={tc('columns.name')} htmlFor="origin-name" error={errors.name?.message} required>
                <input
                  id="origin-name"
                  className={`form-control${errors.name ? ' is-invalid' : ''}`}
                  {...register('name', {
                    required: tv('required'),
                    maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField label={tc('columns.type')} htmlFor="origin-type" error={errors.type?.message} required>
                <select id="origin-type" className="form-select" {...register('type', { required: true })}>
                  {ORIGIN_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {enumLabels.originType(type)}
                    </option>
                  ))}
                </select>
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.location')}</legend>
          <div className="row">
            <div className="col-12">
              <FormField label={tc('columns.address')} htmlFor="origin-address" error={errors.address?.message}>
                <input
                  id="origin-address"
                  className={`form-control${errors.address ? ' is-invalid' : ''}`}
                  {...register('address', { maxLength: { value: 500, message: tv('maxLength', { count: 500 }) } })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.latitude')} htmlFor="origin-latitude" error={errors.latitude?.message}>
                <input
                  id="origin-latitude"
                  type="text"
                  inputMode="decimal"
                  placeholder={tc('placeholders.latitude')}
                  className={`form-control${errors.latitude ? ' is-invalid' : ''}`}
                  {...register('latitude', { validate: validateLatitude })}
                />
              </FormField>
            </div>
            <div className="col-6 col-sm-3">
              <FormField label={tc('fields.longitude')} htmlFor="origin-longitude" error={errors.longitude?.message}>
                <input
                  id="origin-longitude"
                  type="text"
                  inputMode="decimal"
                  placeholder={tc('placeholders.longitude')}
                  className={`form-control${errors.longitude ? ' is-invalid' : ''}`}
                  {...register('longitude', { validate: validateLongitude })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField label={tc('columns.timeZone')} htmlFor="origin-timezone" error={errors.timeZone?.message} required>
                <input
                  id="origin-timezone"
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
          <FormField
            label={tc('fields.externalReference')}
            htmlFor="origin-external-reference"
            error={errors.externalReference?.message}
          >
            <input
              id="origin-external-reference"
              className={`form-control${errors.externalReference ? ' is-invalid' : ''}`}
              placeholder={tc('placeholders.externalReference')}
              {...register('externalReference', { maxLength: { value: 100, message: tv('maxLength', { count: 100 }) } })}
            />
          </FormField>
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
