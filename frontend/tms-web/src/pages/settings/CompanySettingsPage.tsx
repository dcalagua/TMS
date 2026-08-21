import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import {
  fetchCompanyProfile,
  updateCompanyProfile,
  type CompanyProfileRequest,
} from '../../shared/api/administrationApi'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import { AppCard, ErrorState, FormField, LoadingState, PageHeader, StatusBadge } from '../../shared/ui/components'
import { notifySuccess } from '../../shared/ui/alerts'
import { CompanyCreateDrawer } from './CompanyCreateDrawer'

const FORM_ID = 'company-settings-form'

/** Both mirror the CHECK constraints of migration V34, so the form refuses what the database would. */
const COUNTRY_PATTERN = /^[A-Za-z]{2}$/
const PREFIX_PATTERN = /^[A-Za-z][A-Za-z0-9]{0,5}-$/

interface CompanyFormValues {
  name: string
  taxIdentifier: string
  timeZone: string
  defaultCountry: string
  orderNumberPrefix: string
  shipmentNumberPrefix: string
}

const KNOWN_FIELDS = new Set<keyof CompanyFormValues>([
  'name', 'taxIdentifier', 'timeZone', 'defaultCountry', 'orderNumberPrefix', 'shipmentNumberPrefix',
])

/**
 * The tenant's own screen: what this company is called, which zone its operating day is measured
 * in, and what the documents it produces are named.
 *
 * The two prefixes get a live sample underneath them rather than an explanation, because "TO-" is
 * abstract and `TO-00000042` is not. The sample is rendered from the field's current value, so it
 * answers the question before the form is saved.
 */
export function CompanySettingsPage() {
  const { t } = useTranslation('settings')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const { selected, hasPermission, refetch: refetchCompanies } = useCompany()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('iam.company:manage')
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const [showCreate, setShowCreate] = useState(false)

  const profileQuery = useQuery({
    queryKey: ['company-profile', companyId],
    queryFn: ({ signal }) => fetchCompanyProfile(companyId, signal),
    enabled: companyId !== '',
  })

  const {
    register,
    handleSubmit,
    reset,
    setError,
    watch,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<CompanyFormValues>({
    defaultValues: {
      name: '', taxIdentifier: '', timeZone: '', defaultCountry: '',
      orderNumberPrefix: '', shipmentNumberPrefix: '',
    },
  })

  const profile = profileQuery.data

  // The form is populated from the response rather than initialised with it, because the query is
  // still pending on first render and `defaultValues` is only read once.
  useEffect(() => {
    if (!profile) return
    reset({
      name: profile.name,
      taxIdentifier: profile.taxIdentifier ?? '',
      timeZone: profile.timeZone,
      defaultCountry: profile.settings.defaultCountry,
      orderNumberPrefix: profile.settings.orderNumberPrefix,
      shipmentNumberPrefix: profile.settings.shipmentNumberPrefix,
    })
  }, [profile, reset])

  async function onSubmit(values: CompanyFormValues) {
    setFormError(null)
    const request: CompanyProfileRequest = {
      name: values.name.trim(),
      taxIdentifier: values.taxIdentifier.trim() || null,
      timeZone: values.timeZone.trim(),
      defaultCountry: values.defaultCountry.trim().toUpperCase(),
      orderNumberPrefix: values.orderNumberPrefix.trim().toUpperCase(),
      shipmentNumberPrefix: values.shipmentNumberPrefix.trim().toUpperCase(),
    }
    try {
      await updateCompanyProfile(companyId, request)
      notifySuccess(t('company.saved'))
      void queryClient.invalidateQueries({ queryKey: ['company-profile', companyId] })
      // The company switcher shows the name and the shell measures "today" in the time zone, so a
      // change to either has to reach `/me` and not only this screen.
      refetchCompanies()
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  if (profileQuery.isPending) {
    return <LoadingState />
  }
  if (profileQuery.isError || !profile) {
    return (
      <ErrorState
        message={describeApiError(profileQuery.error as ApiError)}
        onRetry={() => void profileQuery.refetch()}
      />
    )
  }

  const orderSample = `${(watch('orderNumberPrefix') || profile.settings.orderNumberPrefix).toUpperCase()}00000042`
  const shipmentSample =
    `${(watch('shipmentNumberPrefix') || profile.settings.shipmentNumberPrefix).toUpperCase()}00000042`

  return (
    // The form wraps the page header as well as the cards, so the Save button in the header is a
    // plain descendant submit rather than one associated by the `form` attribute. It keeps the
    // `form` attribute too - it is correct either way - but the DOM relationship is what actually
    // has to hold, and a header that lives outside the element it submits is the kind of thing that
    // works until a wrapper is introduced between them.
    <form id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
      <PageHeader
        icon="building-gear"
        title={t('company.title')}
        description={t('company.description')}
        meta={
          <>
            <StatusBadge label={profile.code} tone="neutral" />
            {!profile.organizationActive && (
              <StatusBadge label={t('company.organizationInactive')} tone="danger" />
            )}
          </>
        }
        actions={
          <>
            {profile.canCreateCompany && (
              <button
                type="button"
                className="btn btn-outline-secondary btn-sm d-inline-flex align-items-center gap-2"
                onClick={() => setShowCreate(true)}
              >
                <i className="bi bi-plus-square" aria-hidden="true" />
                {t('company.newCompany')}
              </button>
            )}
            {canManage && (
              <button type="submit" form={FORM_ID} className="btn btn-primary btn-sm" disabled={isSubmitting || !isDirty}>
                {isSubmitting ? tc('actions.saving') : tc('actions.save')}
              </button>
            )}
          </>
        }
      />

      {formError && (
        <div className="alert alert-danger py-2 small" role="alert">
          {formError}
        </div>
      )}

      <div className="row g-3">
          <div className="col-12 col-xl-6">
            <AppCard title={t('company.identity')}>
              <FormField label={t('company.code')} htmlFor="company-code" help={t('company.codeHelp')}>
                <input id="company-code" className="form-control" value={profile.code} readOnly disabled />
              </FormField>
              <FormField label={t('company.organization')} htmlFor="company-organization">
                <input
                  id="company-organization"
                  className="form-control"
                  value={`${profile.organization.code} — ${profile.organization.name}`}
                  readOnly
                  disabled
                />
              </FormField>
              <FormField label={tc('columns.name')} htmlFor="company-name" error={errors.name?.message} required>
                <input
                  id="company-name"
                  className={`form-control${errors.name ? ' is-invalid' : ''}`}
                  disabled={!canManage}
                  {...register('name', {
                    required: tv('required'),
                    maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                  })}
                />
              </FormField>
              <FormField
                label={t('company.taxIdentifier')}
                htmlFor="company-tax-identifier"
                error={errors.taxIdentifier?.message}
              >
                <input
                  id="company-tax-identifier"
                  className={`form-control${errors.taxIdentifier ? ' is-invalid' : ''}`}
                  disabled={!canManage}
                  {...register('taxIdentifier', {
                    maxLength: { value: 60, message: tv('maxLength', { count: 60 }) },
                  })}
                />
              </FormField>
            </AppCard>
          </div>

          <div className="col-12 col-xl-6">
            <AppCard title={t('company.operation')}>
              <FormField
                label={t('company.timeZone')}
                htmlFor="company-time-zone"
                error={errors.timeZone?.message}
                help={t('company.timeZoneHelp')}
                required
              >
                <input
                  id="company-time-zone"
                  className={`form-control${errors.timeZone ? ' is-invalid' : ''}`}
                  placeholder="America/Lima"
                  disabled={!canManage}
                  {...register('timeZone', {
                    required: tv('required'),
                    maxLength: { value: 60, message: tv('maxLength', { count: 60 }) },
                  })}
                />
              </FormField>
              <FormField
                label={t('company.defaultCountry')}
                htmlFor="company-default-country"
                error={errors.defaultCountry?.message}
                help={t('company.defaultCountryHelp')}
                required
              >
                <input
                  id="company-default-country"
                  className={`form-control${errors.defaultCountry ? ' is-invalid' : ''}`}
                  placeholder="PE"
                  maxLength={2}
                  disabled={!canManage}
                  {...register('defaultCountry', {
                    required: tv('required'),
                    pattern: { value: COUNTRY_PATTERN, message: t('company.countryPattern') },
                  })}
                />
              </FormField>
            </AppCard>
          </div>

          <div className="col-12">
            <AppCard title={t('company.numbering')}>
              <p className="text-body-secondary small mb-3">{t('company.numberingHelp')}</p>
              <div className="row">
                <div className="col-12 col-sm-6">
                  <FormField
                    label={t('company.orderPrefix')}
                    htmlFor="company-order-prefix"
                    error={errors.orderNumberPrefix?.message}
                    help={t('company.sample', { value: orderSample })}
                    required
                  >
                    <input
                      id="company-order-prefix"
                      className={`form-control${errors.orderNumberPrefix ? ' is-invalid' : ''}`}
                      placeholder="TO-"
                      maxLength={7}
                      disabled={!canManage}
                      {...register('orderNumberPrefix', {
                        required: tv('required'),
                        pattern: { value: PREFIX_PATTERN, message: t('company.prefixPattern') },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-12 col-sm-6">
                  <FormField
                    label={t('company.shipmentPrefix')}
                    htmlFor="company-shipment-prefix"
                    error={errors.shipmentNumberPrefix?.message}
                    help={t('company.sample', { value: shipmentSample })}
                    required
                  >
                    <input
                      id="company-shipment-prefix"
                      className={`form-control${errors.shipmentNumberPrefix ? ' is-invalid' : ''}`}
                      placeholder="SH-"
                      maxLength={7}
                      disabled={!canManage}
                      {...register('shipmentNumberPrefix', {
                        required: tv('required'),
                        pattern: { value: PREFIX_PATTERN, message: t('company.prefixPattern') },
                      })}
                    />
                  </FormField>
                </div>
              </div>
            </AppCard>
          </div>
      </div>

      {/* Portalled to the body by TmsDrawer, so its own form is never nested inside this one. */}
      {showCreate && (
        <CompanyCreateDrawer
          companyId={companyId}
          organizationName={profile.organization.name}
          onClose={() => setShowCreate(false)}
          onCreated={(created) => {
            setShowCreate(false)
            notifySuccess(t('company.created'), `${created.code} — ${created.name}`)
            // The creator holds an organization-wide membership, so the new company is already
            // selectable - but only after `/me` is read again.
            refetchCompanies()
          }}
        />
      )}
    </form>
  )
}
