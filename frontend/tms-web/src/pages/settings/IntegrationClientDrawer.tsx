import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { fetchCarriers } from '../../shared/api/carriersApi'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import {
  createIntegrationClient,
  updateIntegrationClient,
  type IntegrationClientSecretView,
  type IntegrationClientView,
} from '../../shared/api/integrationsApi'
import { FormField } from '../../shared/ui/components/FormField'
import { LookupField, type LookupOption } from '../../shared/ui/components/LookupField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'integration-client-form'

/**
 * The scope catalogue, mirroring the backend's `IntegrationScope`.
 *
 * Hard-coded here, unlike the webhook event types, and the difference is deliberate: a scope is a
 * capability with a security meaning that has to be explained in the operator's own language before
 * anybody grants it, so each one needs a translated description that a list of codes from an
 * endpoint could not carry. Adding a scope is already a migration plus an enum constant; adding the
 * two strings here is the third step and the one that keeps the screen honest.
 */
const SCOPES = [
  { code: 'integration.location:write', key: 'locationWrite' },
  { code: 'integration.order:write', key: 'orderWrite' },
  { code: 'integration.shipment:read', key: 'shipmentRead' },
  { code: 'integration.tracking:write', key: 'trackingWrite' },
  { code: 'integration.tender:respond', key: 'tenderRespond' },
] as const

/** The one scope that is meaningless without a carrier - see the backend's `IntegrationScope`. */
const TENDER_SCOPE = 'integration.tender:respond'

interface ClientFormValues {
  name: string
  description: string
}

const KNOWN_FIELDS = new Set<keyof ClientFormValues>(['name', 'description'])

interface IntegrationClientDrawerProps {
  companyId: string
  /** `null` issues a new credential; otherwise the drawer edits this one. */
  client: IntegrationClientView | null
  onClose: () => void
  /** Called with the issued secret on creation, and with `null` on an edit, which reveals nothing. */
  onSaved: (secret: IntegrationClientSecretView | null) => void
}

/**
 * Issue a machine credential, or change what an existing one may do.
 *
 * The secret is never in this form. It is generated server-side and returned once, by the response
 * to the create - letting an administrator choose one would put a human-memorable string where 256
 * bits of entropy belong.
 *
 * Scopes are checkboxes with a sentence each, not a multi-select of codes. Somebody is deciding
 * whether a partner may write orders into this company unattended, and `integration.order:write`
 * does not say that to a person reading it for the first time.
 */
export function IntegrationClientDrawer({ companyId, client, onClose, onSaved }: IntegrationClientDrawerProps) {
  const { t } = useTranslation('settings')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const isEdit = client !== null
  const [formError, setFormError] = useState<string | null>(null)
  const [selectedScopes, setSelectedScopes] = useState<string[]>(client?.scopes ?? [])
  const [scopesTouched, setScopesTouched] = useState(false)
  // The view carries the carrier's id and not its name - it is assembled from the entity alone -
  // so an existing binding shows as "selected" with no label until the operator picks again. That
  // is honest and cheap; resolving it would put a fleet lookup on the credential list behind it.
  const [carrier, setCarrier] = useState<LookupOption | null>(null)
  const [carrierId, setCarrierId] = useState<string>(client?.carrierId ?? '')
  const [carrierTouched, setCarrierTouched] = useState(false)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ClientFormValues>({
    defaultValues: {
      name: client?.name ?? '',
      description: client?.description ?? '',
    },
  })

  const answersTenders = selectedScopes.includes(TENDER_SCOPE)

  function toggleScope(code: string) {
    setScopesTouched(true)
    setSelectedScopes((current) =>
      current.includes(code) ? current.filter((held) => held !== code) : [...current, code],
    )
  }

  async function onSubmit(values: ClientFormValues) {
    setFormError(null)
    if (selectedScopes.length === 0) {
      setFormError(t('integrations.clients.form.scopeRequired'))
      return
    }
    if (answersTenders && !carrierId) {
      setFormError(t('integrations.clients.form.carrierRequired'))
      return
    }
    const request = {
      name: values.name.trim(),
      description: values.description.trim() || null,
      scopes: selectedScopes,
      // Sent only when it means something. The backend refuses a carrier on a credential that
      // cannot answer tenders, which is the same rule stated once more here so the form does not
      // have to be corrected by a 400.
      carrierId: answersTenders ? carrierId : null,
    }

    try {
      if (isEdit) {
        await updateIntegrationClient(companyId, client.id, request)
        onSaved(null)
      } else {
        onSaved(await createIntegrationClient(companyId, request))
      }
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('integrations.clients.form.edit') : t('integrations.clients.form.create')}
      subtitle={isEdit ? t('integrations.clients.form.editSubtitle') : t('integrations.clients.form.createSubtitle')}
      size="md"
      onClose={onClose}
      dirty={isDirty || scopesTouched || carrierTouched}
      closeOnEscape={!isSubmitting}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={isSubmitting}>
            {tc('actions.cancel')}
          </button>
          <button type="submit" form={FORM_ID} className="btn btn-primary" disabled={isSubmitting}>
            {isSubmitting ? tc('actions.saving') : isEdit ? tc('actions.save') : t('integrations.clients.issue')}
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
          label={tc('columns.name')}
          htmlFor="integration-client-name"
          error={errors.name?.message}
          help={t('integrations.clients.form.nameHelp')}
          required
        >
          <input
            id="integration-client-name"
            className={`form-control${errors.name ? ' is-invalid' : ''}`}
            {...register('name', {
              required: tv('required'),
              maxLength: { value: 120, message: tv('maxLength', { count: 120 }) },
            })}
          />
        </FormField>

        <FormField
          label={tc('columns.description')}
          htmlFor="integration-client-description"
          error={errors.description?.message}
        >
          <input
            id="integration-client-description"
            className={`form-control${errors.description ? ' is-invalid' : ''}`}
            {...register('description', {
              maxLength: { value: 500, message: tv('maxLength', { count: 500 }) },
            })}
          />
        </FormField>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{t('integrations.clients.scopes')}</legend>
          {SCOPES.map((scope) => (
            <div className="form-check" key={scope.code}>
              <input
                className="form-check-input"
                type="checkbox"
                id={`integration-scope-${scope.key}`}
                checked={selectedScopes.includes(scope.code)}
                onChange={() => toggleScope(scope.code)}
              />
              <label className="form-check-label" htmlFor={`integration-scope-${scope.key}`}>
                <span className="fw-semibold">{t(`integrations.scopes.${scope.key}.label`)}</span>
                <code className="d-block small text-body-secondary">{scope.code}</code>
                <span className="d-block small text-body-secondary">
                  {t(`integrations.scopes.${scope.key}.help`)}
                </span>
              </label>
            </div>
          ))}
        </fieldset>

        {answersTenders && (
          <FormField
            label={tc('columns.carrier')}
            htmlFor="integration-client-carrier"
            help={t('integrations.clients.form.carrierHelp')}
            required
          >
            <LookupField
              id="integration-client-carrier"
              value={carrierId}
              selected={carrier}
              onChange={(option) => {
                setCarrierTouched(true)
                setCarrier(option)
                setCarrierId(option?.id ?? '')
              }}
              search={async (term, signal) => {
                const page = await fetchCarriers({
                  companyId,
                  size: 20,
                  sort: 'businessName,asc',
                  // Only an active carrier may be bound: the backend resolves through
                  // `findActiveInCompany` and would refuse a deactivated one anyway.
                  active: true,
                  businessName: term || undefined,
                  signal,
                })
                return page.content.map((row) => ({ id: row.id, code: row.code, name: row.businessName }))
              }}
              queryKey={['integration-client-carrier-lookup', companyId]}
              placeholder={t('integrations.clients.form.carrierPlaceholder')}
            />
          </FormField>
        )}
      </form>
    </TmsDrawer>
  )
}
