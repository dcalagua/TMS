import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import {
  createWebhookSubscription,
  updateWebhookSubscription,
  type WebhookSubscriptionSecretView,
  type WebhookSubscriptionView,
} from '../../shared/api/integrationsApi'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'webhook-subscription-form'

interface WebhookFormValues {
  name: string
  description: string
  targetUrl: string
}

const KNOWN_FIELDS = new Set<keyof WebhookFormValues>(['name', 'description', 'targetUrl'])

interface WebhookSubscriptionDrawerProps {
  companyId: string
  /** `null` creates an endpoint; otherwise the drawer edits this one. */
  subscription: WebhookSubscriptionView | null
  /** The vocabulary, read from the backend so a new event type needs no frontend release. */
  eventTypes: string[]
  onClose: () => void
  /** Called with the signing secret on creation, and with `null` on an edit, which reveals nothing. */
  onSaved: (secret: WebhookSubscriptionSecretView | null) => void
}

/**
 * Point an endpoint at this company's events.
 *
 * Two things this form deliberately cannot do. It cannot set the signing secret - that is generated
 * server-side and shown once. And it cannot switch the endpoint on or off: pausing and resuming are
 * their own actions with their own audit rows, so saving a rename from a screen opened before an
 * automatic suspension cannot silently undo it.
 *
 * The event types come from the backend rather than from a constant here, unlike the credential
 * scopes: a scope is a security capability that needs explaining in prose, while an event type is a
 * fact about the business that either happened or did not, and its name says so.
 */
export function WebhookSubscriptionDrawer({
  companyId,
  subscription,
  eventTypes,
  onClose,
  onSaved,
}: WebhookSubscriptionDrawerProps) {
  const { t } = useTranslation('settings')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const isEdit = subscription !== null
  const [formError, setFormError] = useState<string | null>(null)
  const [selected, setSelected] = useState<string[]>(subscription?.eventTypes ?? [])
  const [eventsTouched, setEventsTouched] = useState(false)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<WebhookFormValues>({
    defaultValues: {
      name: subscription?.name ?? '',
      description: subscription?.description ?? '',
      targetUrl: subscription?.targetUrl ?? '',
    },
  })

  function toggle(eventType: string) {
    setEventsTouched(true)
    setSelected((current) =>
      current.includes(eventType) ? current.filter((held) => held !== eventType) : [...current, eventType],
    )
  }

  function selectAll() {
    setEventsTouched(true)
    setSelected(eventTypes)
  }

  async function onSubmit(values: WebhookFormValues) {
    setFormError(null)
    if (selected.length === 0) {
      setFormError(t('integrations.webhooks.form.eventRequired'))
      return
    }
    const request = {
      name: values.name.trim(),
      description: values.description.trim() || null,
      targetUrl: values.targetUrl.trim(),
      eventTypes: selected,
    }

    try {
      if (isEdit) {
        await updateWebhookSubscription(companyId, subscription.id, request)
        onSaved(null)
      } else {
        onSaved(await createWebhookSubscription(companyId, request))
      }
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('integrations.webhooks.form.edit') : t('integrations.webhooks.form.create')}
      subtitle={isEdit ? t('integrations.webhooks.form.editSubtitle') : t('integrations.webhooks.form.createSubtitle')}
      size="md"
      onClose={onClose}
      dirty={isDirty || eventsTouched}
      closeOnEscape={!isSubmitting}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={isSubmitting}>
            {tc('actions.cancel')}
          </button>
          <button type="submit" form={FORM_ID} className="btn btn-primary" disabled={isSubmitting}>
            {isSubmitting ? tc('actions.saving') : isEdit ? tc('actions.save') : t('integrations.webhooks.add')}
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
          htmlFor="webhook-name"
          error={errors.name?.message}
          help={t('integrations.webhooks.form.nameHelp')}
          required
        >
          <input
            id="webhook-name"
            className={`form-control${errors.name ? ' is-invalid' : ''}`}
            {...register('name', {
              required: tv('required'),
              maxLength: { value: 120, message: tv('maxLength', { count: 120 }) },
            })}
          />
        </FormField>

        <FormField
          label={t('integrations.webhooks.form.targetUrl')}
          htmlFor="webhook-target-url"
          error={errors.targetUrl?.message}
          help={t('integrations.webhooks.form.targetUrlHelp')}
          required
        >
          <input
            id="webhook-target-url"
            type="url"
            inputMode="url"
            className={`form-control${errors.targetUrl ? ' is-invalid' : ''}`}
            placeholder="https://erp.example.com/tms/webhooks"
            {...register('targetUrl', {
              required: tv('required'),
              maxLength: { value: 2048, message: tv('maxLength', { count: 2048 }) },
            })}
          />
        </FormField>

        <FormField
          label={tc('columns.description')}
          htmlFor="webhook-description"
          error={errors.description?.message}
        >
          <input
            id="webhook-description"
            className={`form-control${errors.description ? ' is-invalid' : ''}`}
            {...register('description', {
              maxLength: { value: 500, message: tv('maxLength', { count: 500 }) },
            })}
          />
        </FormField>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend d-flex align-items-center justify-content-between gap-2">
            <span>{t('integrations.webhooks.events')}</span>
            <button type="button" className="btn btn-link btn-sm p-0" onClick={selectAll}>
              {t('integrations.webhooks.form.selectAll')}
            </button>
          </legend>
          <p className="form-text mt-0">{t('integrations.webhooks.form.eventsHelp')}</p>
          {eventTypes.map((eventType) => (
            <div className="form-check" key={eventType}>
              <input
                className="form-check-input"
                type="checkbox"
                id={`webhook-event-${eventType}`}
                checked={selected.includes(eventType)}
                onChange={() => toggle(eventType)}
              />
              <label className="form-check-label" htmlFor={`webhook-event-${eventType}`}>
                <code>{eventType}</code>
              </label>
            </div>
          ))}
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
