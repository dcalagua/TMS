import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { fetchCarriers } from '../../shared/api/carriersApi'
import { createDriver, updateDriver, type DriverRequest, type DriverView } from '../../shared/api/driversApi'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'driver-form'

/** Matches the backend's `code` constraint; kept next to the field it validates. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/

interface DriverFormValues {
  code: string
  firstName: string
  lastName: string
  documentType: string
  documentNumber: string
  phone: string
  licenseNumber: string
  licenseCategory: string
  licenseExpiresOn: string
  carrierId: string
}

interface DriverFormDrawerProps {
  companyId: string
  /** `null` creates a new driver; otherwise the form edits this one. */
  driver: DriverView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof DriverFormValues>([
  'code', 'firstName', 'lastName', 'documentType', 'documentNumber', 'phone', 'licenseNumber',
  'licenseCategory', 'licenseExpiresOn', 'carrierId',
])

interface CarrierOption {
  id: string
  businessName: string
}

/** Keeps the driver's current carrier in the list even after it is deactivated - the same
 * `withCurrent*` pattern `TripVehicleDrawer` and `RouteFormDrawer` use, and load-bearing for the
 * same reason: the select would otherwise reset itself to "own staff" while the list loads. */
function withCurrentCarrier(options: CarrierOption[], driver: DriverView | null): CarrierOption[] {
  if (!driver?.carrierId || options.some((option) => option.id === driver.carrierId)) {
    return options
  }
  return [{ id: driver.carrierId, businessName: driver.carrierBusinessName ?? driver.carrierId }, ...options]
}

/** Create and edit share one form; see `CarrierFormDrawer` for the pattern this follows.
 *
 * The licence expiry is deliberately optional and says so under the field: a company migrating a
 * spreadsheet rarely has it for everyone, and an empty box must not read as a trap that will
 * later block a dispatch. Only an expiry that has actually passed does that. */
export function DriverFormDrawer({ companyId, driver, onClose, onSaved }: DriverFormDrawerProps) {
  const { t } = useTranslation('fleet')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const isEdit = driver !== null
  const [formError, setFormError] = useState<string | null>(null)

  const carriersQuery = useQuery({
    queryKey: ['carriers-for-driver-form', companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
    enabled: companyId !== '',
  })
  const carriers = withCurrentCarrier(carriersQuery.data?.content ?? [], driver)

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<DriverFormValues>({
    defaultValues: {
      code: driver?.code ?? '',
      firstName: driver?.firstName ?? '',
      lastName: driver?.lastName ?? '',
      documentType: driver?.documentType ?? 'DNI',
      documentNumber: driver?.documentNumber ?? '',
      phone: driver?.phone ?? '',
      licenseNumber: driver?.licenseNumber ?? '',
      licenseCategory: driver?.licenseCategory ?? '',
      licenseExpiresOn: driver?.licenseExpiresOn ?? '',
      carrierId: driver?.carrierId ?? '',
    },
  })

  async function onSubmit(values: DriverFormValues) {
    setFormError(null)
    const request: DriverRequest = {
      code: values.code.trim(),
      firstName: values.firstName.trim(),
      lastName: values.lastName.trim(),
      documentType: values.documentType.trim(),
      documentNumber: values.documentNumber.trim(),
      phone: values.phone.trim() || null,
      licenseNumber: values.licenseNumber.trim(),
      licenseCategory: values.licenseCategory.trim() || null,
      licenseExpiresOn: values.licenseExpiresOn || null,
      carrierId: values.carrierId || null,
    }

    try {
      if (isEdit) {
        await updateDriver(companyId, driver.id, request)
      } else {
        await createDriver(companyId, request)
      }
      onSaved()
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('drivers.form.edit') : t('drivers.form.create')}
      subtitle={t('drivers.form.subtitle')}
      size="md"
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
            <div className="col-12 col-sm-4">
              <FormField label={tc('columns.code')} htmlFor="driver-code" error={errors.code?.message} required>
                <input
                  id="driver-code"
                  className={`form-control${errors.code ? ' is-invalid' : ''}`}
                  {...register('code', {
                    required: tv('required'),
                    maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                    pattern: { value: CODE_PATTERN, message: tv('codePattern') },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={t('drivers.form.firstName')}
                htmlFor="driver-first-name"
                error={errors.firstName?.message}
                required
              >
                <input
                  id="driver-first-name"
                  className={`form-control${errors.firstName ? ' is-invalid' : ''}`}
                  {...register('firstName', {
                    required: tv('required'),
                    maxLength: { value: 100, message: tv('maxLength', { count: 100 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={t('drivers.form.lastName')}
                htmlFor="driver-last-name"
                error={errors.lastName?.message}
                required
              >
                <input
                  id="driver-last-name"
                  className={`form-control${errors.lastName ? ' is-invalid' : ''}`}
                  {...register('lastName', {
                    required: tv('required'),
                    maxLength: { value: 100, message: tv('maxLength', { count: 100 }) },
                  })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.document')}</legend>
          <div className="row">
            <div className="col-12 col-sm-4">
              <FormField
                label={t('drivers.form.documentType')}
                htmlFor="driver-document-type"
                error={errors.documentType?.message}
                required
              >
                <input
                  id="driver-document-type"
                  className={`form-control${errors.documentType ? ' is-invalid' : ''}`}
                  {...register('documentType', {
                    required: tv('required'),
                    maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={t('drivers.form.documentNumber')}
                htmlFor="driver-document-number"
                error={errors.documentNumber?.message}
                required
              >
                <input
                  id="driver-document-number"
                  className={`form-control${errors.documentNumber ? ' is-invalid' : ''}`}
                  {...register('documentNumber', {
                    required: tv('required'),
                    maxLength: { value: 64, message: tv('maxLength', { count: 64 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField label={tc('columns.phone')} htmlFor="driver-phone" error={errors.phone?.message}>
                <input
                  id="driver-phone"
                  type="tel"
                  className={`form-control${errors.phone ? ' is-invalid' : ''}`}
                  {...register('phone', { maxLength: { value: 32, message: tv('maxLength', { count: 32 }) } })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{t('drivers.form.sections.license')}</legend>
          <div className="row">
            <div className="col-12 col-sm-5">
              <FormField
                label={t('drivers.form.licenseNumber')}
                htmlFor="driver-license-number"
                error={errors.licenseNumber?.message}
                required
              >
                <input
                  id="driver-license-number"
                  className={`form-control${errors.licenseNumber ? ' is-invalid' : ''}`}
                  {...register('licenseNumber', {
                    required: tv('required'),
                    maxLength: { value: 64, message: tv('maxLength', { count: 64 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-3">
              <FormField
                label={t('drivers.form.licenseCategory')}
                htmlFor="driver-license-category"
                error={errors.licenseCategory?.message}
              >
                <input
                  id="driver-license-category"
                  className={`form-control${errors.licenseCategory ? ' is-invalid' : ''}`}
                  {...register('licenseCategory', {
                    maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={t('drivers.form.licenseExpiresOn')}
                htmlFor="driver-license-expires-on"
                error={errors.licenseExpiresOn?.message}
                help={t('drivers.form.licenseExpiresHelp')}
              >
                <input
                  id="driver-license-expires-on"
                  type="date"
                  className={`form-control${errors.licenseExpiresOn ? ' is-invalid' : ''}`}
                  {...register('licenseExpiresOn')}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{t('drivers.form.sections.employment')}</legend>
          <div className="row">
            <div className="col-12">
              <FormField label={t('drivers.form.carrier')} htmlFor="driver-carrier" error={errors.carrierId?.message}>
                {/* Controller rather than `register`: Select is a button plus a listbox, so there
                    is no native change event for react-hook-form to hook into. */}
                <Controller
                  control={control}
                  name="carrierId"
                  render={({ field }) => (
                    <Select
                      id="driver-carrier"
                      value={field.value}
                      onChange={(next) => field.onChange(next)}
                      options={[
                        { value: '', label: t('drivers.form.ownStaff') },
                        ...carriers.map((carrier) => ({ value: carrier.id, label: carrier.businessName })),
                      ]}
                    />
                  )}
                />
              </FormField>
            </div>
          </div>
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
