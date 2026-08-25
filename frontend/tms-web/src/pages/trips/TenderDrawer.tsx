import { useState } from 'react'
import { useForm, type Validate } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import type { TenderRequest, TripTenderView } from '../../shared/api/tendersApi'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'tender-form'

const CURRENCY_PATTERN = /^[A-Za-z]{3}$/

interface TenderFormValues {
  offeredAmount: string
  currency: string
  notes: string
  expiresAt: string
}

export interface TenderDrawerProps {
  /** The carrier the shipment will be offered to - the trip's own, and never a choice here. */
  carrierName: string | null
  /** The draft being edited, or null when a new offer is being prepared. */
  tender: TripTenderView | null
  onClose: () => void
  onSubmit: (request: TenderRequest) => Promise<void>
}

const KNOWN_FIELDS = new Set<keyof TenderFormValues>(['offeredAmount', 'currency', 'notes', 'expiresAt'])

/**
 * The terms of an offer: what we will pay, by when they must answer, and anything the carrier needs
 * to know.
 *
 * **No carrier picker,** and its absence is the design rather than an omission. A shipment's carrier
 * comes from the vehicle planned on it, and by the time a shipment can be offered that vehicle is
 * fixed - so there is exactly one carrier the offer could go to, and a dropdown with one entry would
 * suggest a choice the product does not have. `docs/domain/CARRIER_TENDERING_V1.md` §3 explains what
 * that costs.
 *
 * The amount and the currency travel together or not at all: the backend refuses one without the
 * other, and a company tendering under a standing rate card has no per-shipment price to state.
 */
export function TenderDrawer({ carrierName, tender, onClose, onSubmit }: TenderDrawerProps) {
  const { t } = useTranslation('trips')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<TenderFormValues>({
    defaultValues: {
      offeredAmount: tender?.offeredAmount?.toString() ?? '',
      currency: tender?.currency ?? '',
      notes: tender?.notes ?? '',
      // <input type="datetime-local"> wants a local wall-clock string with no zone, so the stored
      // instant is trimmed to minutes here and read back through the browser's own zone below.
      expiresAt: toLocalInput(tender?.expiresAt ?? null),
    },
  })

  const amount = watch('offeredAmount')
  const currency = watch('currency')
  const pricing = amount.trim() !== '' || currency.trim() !== ''

  /** Optional as a pair: state both or neither, which is what the backend enforces. */
  const validateAmount: Validate<string, TenderFormValues> = (value) => {
    if (value.trim() === '') {
      return currency.trim() === '' || tv('required')
    }
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    return parsed >= 0 || tv('nonNegative')
  }

  async function submit(values: TenderFormValues) {
    setFormError(null)
    try {
      await onSubmit({
        offeredAmount: values.offeredAmount.trim() === '' ? null : Number(values.offeredAmount),
        currency: values.currency.trim() === '' ? null : values.currency.trim().toUpperCase(),
        notes: values.notes.trim() || null,
        expiresAt: values.expiresAt === '' ? null : new Date(values.expiresAt).toISOString(),
      })
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={tender ? t('tender.form.editTitle') : t('tender.form.title')}
      subtitle={carrierName ?? undefined}
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
      <form id={FORM_ID} onSubmit={(event) => void handleSubmit(submit)(event)} noValidate>
        {formError && (
          <div className="alert alert-danger py-2 small" role="alert">
            {formError}
          </div>
        )}

        <p className="text-secondary small">{t('tender.form.intro')}</p>

        <div className="row">
          <div className="col-12 col-sm-8">
            <FormField
              label={t('tender.form.offeredAmount')}
              htmlFor="tender-amount"
              error={errors.offeredAmount?.message}
              help={t('tender.form.offeredAmountHelp')}
            >
              <input
                id="tender-amount"
                type="text"
                inputMode="decimal"
                className={`form-control${errors.offeredAmount ? ' is-invalid' : ''}`}
                {...register('offeredAmount', { validate: validateAmount })}
              />
            </FormField>
          </div>
          <div className="col-12 col-sm-4">
            <FormField
              label={t('tender.form.currency')}
              htmlFor="tender-currency"
              error={errors.currency?.message}
              required={pricing}
            >
              <input
                id="tender-currency"
                maxLength={3}
                placeholder="PEN"
                className={`form-control text-uppercase${errors.currency ? ' is-invalid' : ''}`}
                {...register('currency', {
                  validate: (value) =>
                    value.trim() === ''
                      ? amount.trim() === '' || tv('required')
                      : CURRENCY_PATTERN.test(value) || t('tender.form.currencyHelp'),
                })}
              />
            </FormField>
          </div>
        </div>

        <FormField
          label={t('tender.form.expiresAt')}
          htmlFor="tender-expires-at"
          error={errors.expiresAt?.message}
          help={t('tender.form.expiresAtHelp')}
        >
          <input
            id="tender-expires-at"
            type="datetime-local"
            className={`form-control${errors.expiresAt ? ' is-invalid' : ''}`}
            {...register('expiresAt')}
          />
        </FormField>

        <FormField
          label={t('tender.form.notes')}
          htmlFor="tender-notes"
          error={errors.notes?.message}
          help={t('tender.form.notesHelp')}
        >
          <textarea
            id="tender-notes"
            rows={3}
            className={`form-control${errors.notes ? ' is-invalid' : ''}`}
            {...register('notes', { maxLength: { value: 1000, message: tv('maxLength', { count: 1000 }) } })}
          />
        </FormField>
      </form>
    </TmsDrawer>
  )
}

/**
 * An ISO instant as the local `YYYY-MM-DDTHH:mm` a `datetime-local` input expects.
 *
 * Local and not UTC on purpose: a planner setting "answer by 12:00" means noon where they are, and
 * a field that showed them 17:00 because the instant is stored in UTC would be the kind of detail
 * that produces a deadline nobody meant.
 */
function toLocalInput(iso: string | null): string {
  if (!iso) return ''
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return ''
  const offsetMs = at.getTimezoneOffset() * 60_000
  return new Date(at.getTime() - offsetMs).toISOString().slice(0, 16)
}
