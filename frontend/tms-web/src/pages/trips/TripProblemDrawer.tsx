import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import {
  STOP_SCOPED_EXCEPTION_TYPES,
  TRIP_EXCEPTION_TYPES,
  type TripExceptionType,
} from '../../shared/api/planningApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'trip-problem-form'

export interface TripProblemStopOption {
  id: string
  label: string
}

export interface TripProblemValues {
  exceptionType: TripExceptionType
  tripStopId: string | null
  notes: string | null
}

interface TripProblemDrawerProps {
  title: string
  subtitle: string
  submitLabel: string
  /**
   * The stops a trip-level report may be attributed to. Omitted when the problem already belongs
   * to a known stop - skipping or failing one - in which case no picker is shown at all.
   */
  stops?: TripProblemStopOption[]
  /** Pre-set and not offered for change: the stop the caller is already acting on. */
  lockedStopId?: string
  onClose: () => void
  onSubmit: (values: TripProblemValues) => Promise<void>
}

/**
 * The one form behind every way of saying "something went wrong": skipping a stop, failing one,
 * and reporting a problem against the trip itself.
 *
 * <p>One component rather than three, because the three differ only in which stop the problem is
 * attached to and in what the button says. The fields - a typed reason and a sentence - are the
 * same, and so is the rule that makes them worth collecting: an untyped reason cannot be counted,
 * and `OTHER` without a sentence says nothing at all.
 *
 * <p>Client-side validation here is a courtesy, never the decision. The server re-checks that a
 * delivery-shaped reason names a stop and that `OTHER` carries notes, and it is the server's
 * refusal that is authoritative - see `TripStopExecutionService`.
 */
export function TripProblemDrawer({
  title,
  subtitle,
  submitLabel,
  stops,
  lockedStopId,
  onClose,
  onSubmit,
}: TripProblemDrawerProps) {
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
  } = useForm<{ exceptionType: TripExceptionType; tripStopId: string; notes: string }>({
    defaultValues: {
      exceptionType: 'TRAFFIC_DELAY',
      tripStopId: lockedStopId ?? '',
      notes: '',
    },
  })

  const selectedType = watch('exceptionType')
  const notesRequired = selectedType === 'OTHER'
  // Only meaningful when a picker is on screen: a locked stop always satisfies the rule.
  const stopRequired = stops !== undefined && STOP_SCOPED_EXCEPTION_TYPES.includes(selectedType)

  async function submit(values: { exceptionType: TripExceptionType; tripStopId: string; notes: string }) {
    setFormError(null)
    try {
      await onSubmit({
        exceptionType: values.exceptionType,
        tripStopId: values.tripStopId === '' ? null : values.tripStopId,
        notes: values.notes.trim() === '' ? null : values.notes.trim(),
      })
    } catch (error) {
      setFormError((error as Error).message)
    }
  }

  return (
    <TmsDrawer
      open
      title={title}
      subtitle={subtitle}
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
            {isSubmitting ? tc('actions.saving') : submitLabel}
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

        <FormField label={t('workspace.problems.type')} htmlFor="trip-problem-type" required>
          <Controller
            control={control}
            name="exceptionType"
            render={({ field }) => (
              <Select
                id="trip-problem-type"
                value={field.value}
                onChange={(next) => field.onChange(next as TripExceptionType)}
                options={TRIP_EXCEPTION_TYPES.map((type) => ({
                  value: type,
                  label: enumLabels.tripExceptionType(type),
                }))}
              />
            )}
          />
        </FormField>

        {stops !== undefined && (
          <FormField
            label={t('workspace.problems.stop')}
            htmlFor="trip-problem-stop"
            required={stopRequired}
            help={t('workspace.problems.stopHelp')}
            error={errors.tripStopId?.message}
          >
            <Controller
              control={control}
              name="tripStopId"
              rules={{
                validate: (value) =>
                  !stopRequired || value !== '' || t('workspace.problems.stopRequired'),
              }}
              render={({ field }) => (
                <Select
                  id="trip-problem-stop"
                  value={field.value}
                  onChange={(next) => field.onChange(next)}
                  options={[
                    { value: '', label: t('workspace.problems.wholeTrip') },
                    ...stops.map((stop) => ({ value: stop.id, label: stop.label })),
                  ]}
                />
              )}
            />
          </FormField>
        )}

        <FormField
          label={t('workspace.problems.notes')}
          htmlFor="trip-problem-notes"
          required={notesRequired}
          help={t('workspace.problems.notesHelp')}
          error={errors.notes?.message}
        >
          <textarea
            id="trip-problem-notes"
            className="form-control"
            rows={3}
            // The tighter of the two server limits (500 on a stop's notes, 1000 on a trip-level
            // report), because one shared form must not offer a length one of its uses refuses.
            maxLength={500}
            {...register('notes', {
              validate: (value) =>
                !notesRequired || value.trim() !== '' || t('workspace.problems.notesRequired'),
            })}
          />
        </FormField>
      </form>
    </TmsDrawer>
  )
}
