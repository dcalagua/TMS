import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchOrigins } from '../../shared/api/originsApi'
import { createPlanningRun, type PlanningRunDetailView, type PlanningRunRequest } from '../../shared/api/planningApi'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'planning-run-form'

interface PlanningRunFormValues {
  originId: string
  planningDate: string
  notes: string
}

interface PlanningRunFormDrawerProps {
  companyId: string
  onClose: () => void
  onCreated: (run: PlanningRunDetailView) => void
}

const KNOWN_FIELDS = new Set<keyof PlanningRunFormValues>(['originId', 'planningDate', 'notes'])

/** Opens a new manual planning run. There is no edit form: a run's origin and date are fixed for
 * its lifetime (`docs/domain/PLANNING_MANUAL_V1.md` section 2) - only its trips and assignments
 * change, which the board (`PlanningBoardPage`) handles, not this modal. */
export function PlanningRunFormDrawer({ companyId, onClose, onCreated }: PlanningRunFormDrawerProps) {
  const { t } = useTranslation('planning')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const [formError, setFormError] = useState<string | null>(null)

  const originsQuery = useQuery({
    queryKey: ['origins-for-planning-run-form', companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const origins = originsQuery.data?.content ?? []

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<PlanningRunFormValues>({ defaultValues: { originId: '', planningDate: '', notes: '' } })

  async function onSubmit(values: PlanningRunFormValues) {
    setFormError(null)
    const request: PlanningRunRequest = {
      originId: values.originId,
      planningDate: values.planningDate,
      notes: values.notes.trim() === '' ? null : values.notes.trim(),
    }

    try {
      const run = await createPlanningRun(companyId, request)
      onCreated(run)
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={t('run.form.create')}
      subtitle={t('run.form.subtitle')}
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
            {isSubmitting ? t('run.form.submitting') : t('run.form.submit')}
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

        <div className="row">
          <div className="col-12 col-md-7">
            <FormField label={t('run.form.origin')} htmlFor="run-origin" error={errors.originId?.message} required>
              {/* Controller rather than `register`: the control is a button plus a listbox, so
                  there is no native change event for react-hook-form to hook into. */}
              <Controller
                control={control}
                name="originId"
                rules={{ required: tv('required') }}
                render={({ field }) => (
                  <Select
                    id="run-origin"
                    value={field.value}
                    onChange={(next) => field.onChange(next)}
                    invalid={Boolean(errors.originId)}
                    options={[
                      { value: '', label: t('run.form.selectOrigin') },
                      ...origins.map((origin) => ({ value: origin.id, label: origin.name })),
                    ]}
                  />
                )}
              />
            </FormField>
          </div>
          <div className="col-12 col-md-5">
            <FormField label={t('run.form.date')} htmlFor="run-date" error={errors.planningDate?.message} required>
              <input
                id="run-date"
                type="date"
                className={`form-control${errors.planningDate ? ' is-invalid' : ''}`}
                {...register('planningDate', { required: tv('required') })}
              />
            </FormField>
          </div>
        </div>

        <FormField label={t('run.form.notes')} htmlFor="run-notes" error={errors.notes?.message}>
          <textarea
            id="run-notes"
            className={`form-control${errors.notes ? ' is-invalid' : ''}`}
            rows={3}
            {...register('notes', { maxLength: { value: 2000, message: tv('maxLength', { count: 2000 }) } })}
          />
        </FormField>
      </form>
    </TmsDrawer>
  )
}
