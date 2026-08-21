import { useState } from 'react'
import { useForm, type Validate } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import type { ActualCostRequest } from '../../shared/api/ratesApi'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'actual-cost-form'

const CURRENCY_PATTERN = /^[A-Za-z]{3}$/

interface ActualCostFormValues {
  amount: string
  currency: string
  reference: string
  notes: string
}

export interface ActualCostDrawerProps {
  /** The currency already fixed by the estimate, or null when nothing has costed this trip yet. */
  currency: string | null
  amount: number | null
  reference: string | null
  notes: string | null
  onClose: () => void
  onSubmit: (request: ActualCostRequest) => Promise<void>
}

const KNOWN_FIELDS = new Set<keyof ActualCostFormValues>(['amount', 'currency', 'reference', 'notes'])

/**
 * What the carrier actually charged.
 *
 * The currency field appears only when there is none to inherit - a trip no rate card covered,
 * whose invoice is still worth recording. When an estimate exists the currency is settled and
 * showing an editable copy of it would invite a mismatch the backend refuses anyway.
 */
export function ActualCostDrawer({
  currency,
  amount,
  reference,
  notes,
  onClose,
  onSubmit,
}: ActualCostDrawerProps) {
  const { t } = useTranslation('rates')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const [formError, setFormError] = useState<string | null>(null)
  const needsCurrency = currency === null

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ActualCostFormValues>({
    defaultValues: {
      amount: amount?.toString() ?? '',
      currency: currency ?? '',
      reference: reference ?? '',
      notes: notes ?? '',
    },
  })

  const validateAmount: Validate<string, ActualCostFormValues> = (value) => {
    if (value.trim() === '') return tv('required')
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    return parsed >= 0 || tv('nonNegative')
  }

  async function submit(values: ActualCostFormValues) {
    setFormError(null)
    try {
      await onSubmit({
        amount: Number(values.amount),
        currency: needsCurrency ? values.currency.trim().toUpperCase() : null,
        reference: values.reference.trim() || null,
        notes: values.notes.trim() || null,
      })
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={t('tripCost.form.title')}
      subtitle={t('tripCost.form.subtitle')}
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

        <div className="row">
          <div className={needsCurrency ? 'col-12 col-sm-8' : 'col-12'}>
            <FormField
              label={t('tripCost.form.amount')}
              htmlFor="actual-cost-amount"
              error={errors.amount?.message}
              required
              help={currency ?? undefined}
            >
              <input
                id="actual-cost-amount"
                type="text"
                inputMode="decimal"
                className={`form-control${errors.amount ? ' is-invalid' : ''}`}
                {...register('amount', { validate: validateAmount })}
              />
            </FormField>
          </div>
          {needsCurrency && (
            <div className="col-12 col-sm-4">
              <FormField
                label={t('form.currency')}
                htmlFor="actual-cost-currency"
                error={errors.currency?.message}
                required
                help={t('tripCost.form.currencyHelp')}
              >
                <input
                  id="actual-cost-currency"
                  maxLength={3}
                  placeholder="PEN"
                  className={`form-control text-uppercase${errors.currency ? ' is-invalid' : ''}`}
                  {...register('currency', {
                    required: tv('required'),
                    pattern: { value: CURRENCY_PATTERN, message: t('form.currencyHelp') },
                  })}
                />
              </FormField>
            </div>
          )}
        </div>

        <FormField
          label={t('tripCost.form.reference')}
          htmlFor="actual-cost-reference"
          error={errors.reference?.message}
          help={t('tripCost.form.referenceHelp')}
        >
          <input
            id="actual-cost-reference"
            className={`form-control${errors.reference ? ' is-invalid' : ''}`}
            {...register('reference', { maxLength: { value: 100, message: tv('maxLength', { count: 100 }) } })}
          />
        </FormField>

        <FormField label={tc('sections.notes')} htmlFor="actual-cost-notes" error={errors.notes?.message}>
          <textarea
            id="actual-cost-notes"
            rows={3}
            className={`form-control${errors.notes ? ' is-invalid' : ''}`}
            {...register('notes', { maxLength: { value: 1000, message: tv('maxLength', { count: 1000 }) } })}
          />
        </FormField>
      </form>
    </TmsDrawer>
  )
}
