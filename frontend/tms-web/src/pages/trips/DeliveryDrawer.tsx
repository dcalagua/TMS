import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import {
  DELIVERY_RESULTS,
  DELIVERY_RESULTS_NEEDING_NOTES,
  DELIVERY_RESULTS_NEEDING_TIME,
  DELIVERY_RESULTS_WITH_RECEIVER,
  type DeliveryResult,
  type OrderDeliveryView,
} from '../../shared/api/planningApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'delivery-result-form'

export interface DeliveryValues {
  result: DeliveryResult
  /** A `datetime-local` value, or null. The caller converts it to the ISO instant the API takes. */
  deliveredAt: string | null
  receiverName: string | null
  receiverDocument: string | null
  notes: string | null
}

interface DeliveryDrawerProps {
  /** "3. Supermercado Centro" - which stop this delivery happened at. */
  stopLabel: string
  orderNumber: string
  /** The delivery being corrected, or undefined when recording one for the first time. */
  existing?: OrderDeliveryView
  onClose: () => void
  onSubmit: (values: DeliveryValues) => Promise<void>
}

/**
 * An ISO instant turned back into the `datetime-local` value an input can show, in the operator's
 * own time zone - the inverse of the workspace's `toInstant`. Returns an empty string for null,
 * which is what an untouched input holds.
 */
function toLocalInput(iso: string | null): string {
  if (iso === null) return ''
  const parsed = new Date(iso)
  if (Number.isNaN(parsed.getTime())) return ''
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}`
    + `T${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
}

/**
 * Recording what was handed over to one customer, for one order, at one stop
 * (`docs/domain/PROOF_OF_DELIVERY_V1.md`).
 *
 * <p>The form changes shape with the result, because the fields are only meaningful for some of
 * them: nothing was attempted, so there is no time and nobody to name; a failed attempt has no
 * receiver either; and anything short of a clean delivery has to say why. Hiding a field is not the
 * enforcement - the server refuses the same combinations with a message of its own, and it is that
 * refusal which is authoritative - it is what keeps a dispatcher from filling in three boxes that
 * would then be rejected.
 *
 * <p>Correcting an existing delivery uses the same form, pre-filled: a correction is not a
 * different fact, it is the same fact told properly, and the API takes it as one `PUT`.
 */
export function DeliveryDrawer({ stopLabel, orderNumber, existing, onClose, onSubmit }: DeliveryDrawerProps) {
  const { t } = useTranslation('trips')
  const { t: tc } = useTranslation('common')
  const enumLabels = useEnumLabels()
  const [formError, setFormError] = useState<string | null>(null)

  const {
    control,
    register,
    handleSubmit,
    watch,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<{
    result: DeliveryResult
    deliveredAt: string
    receiverName: string
    receiverDocument: string
    notes: string
  }>({
    defaultValues: {
      result: existing?.result ?? 'DELIVERED',
      deliveredAt: toLocalInput(existing?.deliveredAt ?? null),
      receiverName: existing?.receiverName ?? '',
      receiverDocument: existing?.receiverDocument ?? '',
      notes: existing?.notes ?? '',
    },
  })

  const result = watch('result')
  const showsTime = result !== 'NOT_ATTEMPTED'
  const timeRequired = DELIVERY_RESULTS_NEEDING_TIME.includes(result)
  const showsReceiver = DELIVERY_RESULTS_WITH_RECEIVER.includes(result)
  const notesRequired = DELIVERY_RESULTS_NEEDING_NOTES.includes(result)

  async function submit(values: {
    result: DeliveryResult
    deliveredAt: string
    receiverName: string
    receiverDocument: string
    notes: string
  }) {
    setFormError(null)
    const blankToNull = (value: string) => (value.trim() === '' ? null : value.trim())
    try {
      await onSubmit({
        result: values.result,
        // Cleared rather than carried over when the chosen result has no room for it: the API takes
        // the whole state of the delivery, so a leftover value from a previous choice would be sent
        // as if it had been meant.
        deliveredAt: showsTime ? blankToNull(values.deliveredAt) : null,
        receiverName: showsReceiver ? blankToNull(values.receiverName) : null,
        receiverDocument: showsReceiver ? blankToNull(values.receiverDocument) : null,
        notes: blankToNull(values.notes),
      })
    } catch (error) {
      setFormError((error as Error).message)
    }
  }

  return (
    <TmsDrawer
      open
      title={existing === undefined ? t('workspace.deliveries.recordTitle') : t('workspace.deliveries.correctTitle')}
      subtitle={t('workspace.deliveries.subtitle', { order: orderNumber, stop: stopLabel })}
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
            {isSubmitting ? tc('actions.saving') : t('workspace.deliveries.submit')}
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

        <FormField label={t('workspace.deliveries.result')} htmlFor="delivery-result" required>
          <Controller
            control={control}
            name="result"
            render={({ field }) => (
              <Select
                id="delivery-result"
                value={field.value}
                onChange={(next) => field.onChange(next as DeliveryResult)}
                options={DELIVERY_RESULTS.map((value) => ({
                  value,
                  label: enumLabels.deliveryResult(value),
                }))}
              />
            )}
          />
        </FormField>

        {showsTime && (
          <FormField
            label={t('workspace.deliveries.deliveredAt')}
            htmlFor="delivery-delivered-at"
            required={timeRequired}
            help={t('workspace.deliveries.deliveredAtHelp')}
            error={errors.deliveredAt?.message}
          >
            <input
              id="delivery-delivered-at"
              type="datetime-local"
              className="form-control"
              {...register('deliveredAt', {
                validate: (value) =>
                  !timeRequired || value.trim() !== '' || t('workspace.deliveries.deliveredAtRequired'),
              })}
            />
          </FormField>
        )}

        {showsReceiver && (
          <>
            <FormField label={t('workspace.deliveries.receiverName')} htmlFor="delivery-receiver-name">
              <input
                id="delivery-receiver-name"
                type="text"
                className="form-control"
                maxLength={120}
                {...register('receiverName')}
              />
            </FormField>

            <FormField
              label={t('workspace.deliveries.receiverDocument')}
              htmlFor="delivery-receiver-document"
              help={t('workspace.deliveries.receiverDocumentHelp')}
            >
              <input
                id="delivery-receiver-document"
                type="text"
                className="form-control"
                maxLength={60}
                {...register('receiverDocument')}
              />
            </FormField>
          </>
        )}

        <FormField
          label={t('workspace.deliveries.notes')}
          htmlFor="delivery-notes"
          required={notesRequired}
          help={t('workspace.deliveries.notesHelp')}
          error={errors.notes?.message}
        >
          <textarea
            id="delivery-notes"
            className="form-control"
            rows={3}
            maxLength={1000}
            {...register('notes', {
              validate: (value) =>
                !notesRequired || value.trim() !== '' || t('workspace.deliveries.notesRequired'),
            })}
          />
        </FormField>
      </form>
    </TmsDrawer>
  )
}
