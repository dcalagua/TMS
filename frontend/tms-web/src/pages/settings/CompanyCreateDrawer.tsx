import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { createCompany, type CompanyProfileView } from '../../shared/api/administrationApi'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'company-create-form'

/** Mirrors `ck_company_code_shape` (migration V2). */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$/

interface CompanyCreateFormValues {
  code: string
  name: string
  taxIdentifier: string
  timeZone: string
}

const KNOWN_FIELDS = new Set<keyof CompanyCreateFormValues>(['code', 'name', 'taxIdentifier', 'timeZone'])

interface CompanyCreateDrawerProps {
  /** The company the request is scoped to; its organization receives the new one. */
  companyId: string
  organizationName: string
  onClose: () => void
  onCreated: (created: CompanyProfileView) => void
}

/**
 * Adds a company to the organization the caller is signed into.
 *
 * There is no organization picker, and there should not be: the organization is the one the
 * current company belongs to, resolved server-side. A field here would be the only place in the
 * product where a browser names a tenant.
 *
 * The drawer is only reachable when the profile said `canCreateCompany`, which means an
 * organization-wide role. That is UX; the endpoint asks the database again and answers 403 to
 * anyone else, so nothing depends on this component having hidden itself.
 */
export function CompanyCreateDrawer({
  companyId,
  organizationName,
  onClose,
  onCreated,
}: CompanyCreateDrawerProps) {
  const { t } = useTranslation('settings')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<CompanyCreateFormValues>({
    defaultValues: { code: '', name: '', taxIdentifier: '', timeZone: 'America/Lima' },
  })

  async function onSubmit(values: CompanyCreateFormValues) {
    setFormError(null)
    try {
      const created = await createCompany(companyId, {
        code: values.code.trim().toUpperCase(),
        name: values.name.trim(),
        taxIdentifier: values.taxIdentifier.trim() || null,
        timeZone: values.timeZone.trim(),
      })
      onCreated(created)
    } catch (error) {
      // A 403 from the organization-wide check arrives here with no field errors, so it becomes the
      // form-level message - which is where the person who pressed the button is looking.
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={t('company.newCompany')}
      subtitle={t('company.newCompanySubtitle', { organization: organizationName })}
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
            {isSubmitting ? tc('actions.saving') : tc('actions.create')}
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

        <FormField
          label={tc('columns.code')}
          htmlFor="new-company-code"
          error={errors.code?.message}
          help={t('company.codeHelp')}
          required
        >
          <input
            id="new-company-code"
            className={`form-control${errors.code ? ' is-invalid' : ''}`}
            {...register('code', {
              required: tv('required'),
              maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
              pattern: { value: CODE_PATTERN, message: tv('codePattern') },
            })}
          />
        </FormField>

        <FormField label={tc('columns.name')} htmlFor="new-company-name" error={errors.name?.message} required>
          <input
            id="new-company-name"
            className={`form-control${errors.name ? ' is-invalid' : ''}`}
            {...register('name', {
              required: tv('required'),
              maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
            })}
          />
        </FormField>

        <FormField
          label={t('company.taxIdentifier')}
          htmlFor="new-company-tax-identifier"
          error={errors.taxIdentifier?.message}
        >
          <input
            id="new-company-tax-identifier"
            className={`form-control${errors.taxIdentifier ? ' is-invalid' : ''}`}
            {...register('taxIdentifier', { maxLength: { value: 60, message: tv('maxLength', { count: 60 }) } })}
          />
        </FormField>

        <FormField
          label={t('company.timeZone')}
          htmlFor="new-company-time-zone"
          error={errors.timeZone?.message}
          help={t('company.timeZoneHelp')}
          required
        >
          <input
            id="new-company-time-zone"
            className={`form-control${errors.timeZone ? ' is-invalid' : ''}`}
            placeholder="America/Lima"
            {...register('timeZone', {
              required: tv('required'),
              maxLength: { value: 60, message: tv('maxLength', { count: 60 }) },
            })}
          />
        </FormField>

        <p className="text-body-secondary small mb-0">{t('company.newCompanyNote')}</p>
      </form>
    </TmsDrawer>
  )
}
