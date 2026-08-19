import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import { createCarrier, updateCarrier, type CarrierRequest, type CarrierView } from '../../shared/api/carriersApi'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'carrier-form'

/** Matches the backend's `code` constraint; kept next to the field it validates. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/

interface CarrierFormValues {
  code: string
  businessName: string
  taxIdType: string
  taxIdValue: string
  contactName: string
  phone: string
  email: string
}

interface CarrierFormDrawerProps {
  companyId: string
  /** `null` creates a new carrier; otherwise the form edits this one. */
  carrier: CarrierView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof CarrierFormValues>([
  'code', 'businessName', 'taxIdType', 'taxIdValue', 'contactName', 'phone', 'email',
])

/** Create and edit share one form; see `ZoneFormDrawer` (masters) for the same pattern. */
export function CarrierFormDrawer({ companyId, carrier, onClose, onSaved }: CarrierFormDrawerProps) {
  const { t } = useTranslation('fleet')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const isEdit = carrier !== null
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
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
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('carriers.form.edit') : t('carriers.form.create')}
      subtitle={t('carriers.form.subtitle')}
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
              <FormField label={tc('columns.code')} htmlFor="carrier-code" error={errors.code?.message} required>
                <input
                  id="carrier-code"
                  className={`form-control${errors.code ? ' is-invalid' : ''}`}
                  {...register('code', {
                    required: tv('required'),
                    maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                    pattern: { value: CODE_PATTERN, message: tv('codePattern') },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-8">
              <FormField
                label={tc('columns.businessName')}
                htmlFor="carrier-business-name"
                error={errors.businessName?.message}
                required
              >
                <input
                  id="carrier-business-name"
                  className={`form-control${errors.businessName ? ' is-invalid' : ''}`}
                  {...register('businessName', {
                    required: tv('required'),
                    maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                  })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.document')}</legend>
          <div className="row">
            <div className="col-12 col-sm-5">
              <FormField
                label={tc('fields.taxIdType')}
                htmlFor="carrier-tax-id-type"
                error={errors.taxIdType?.message}
                required
              >
                <input
                  id="carrier-tax-id-type"
                  placeholder={tc('placeholders.taxIdType')}
                  className={`form-control${errors.taxIdType ? ' is-invalid' : ''}`}
                  {...register('taxIdType', {
                    required: tv('required'),
                    maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-7">
              <FormField
                label={tc('fields.taxIdValue')}
                htmlFor="carrier-tax-id-value"
                error={errors.taxIdValue?.message}
                required
              >
                <input
                  id="carrier-tax-id-value"
                  className={`form-control${errors.taxIdValue ? ' is-invalid' : ''}`}
                  {...register('taxIdValue', {
                    required: tv('required'),
                    maxLength: { value: 64, message: tv('maxLength', { count: 64 }) },
                  })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{tc('sections.contact')}</legend>
          <div className="row">
            <div className="col-12 col-sm-12">
              <FormField
                label={tc('fields.contactName')}
                htmlFor="carrier-contact-name"
                error={errors.contactName?.message}
              >
                <input
                  id="carrier-contact-name"
                  className={`form-control${errors.contactName ? ' is-invalid' : ''}`}
                  {...register('contactName', { maxLength: { value: 200, message: tv('maxLength', { count: 200 }) } })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-5">
              <FormField label={tc('columns.phone')} htmlFor="carrier-phone" error={errors.phone?.message}>
                <input
                  id="carrier-phone"
                  type="tel"
                  className={`form-control${errors.phone ? ' is-invalid' : ''}`}
                  {...register('phone', { maxLength: { value: 32, message: tv('maxLength', { count: 32 }) } })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-7">
              <FormField label={tc('fields.email')} htmlFor="carrier-email" error={errors.email?.message}>
                <input
                  id="carrier-email"
                  type="email"
                  className={`form-control${errors.email ? ' is-invalid' : ''}`}
                  {...register('email', { maxLength: { value: 200, message: tv('maxLength', { count: 200 }) } })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
