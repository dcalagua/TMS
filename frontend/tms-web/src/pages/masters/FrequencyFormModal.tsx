import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import {
  createFrequency,
  updateFrequency,
  type FrequencyRequest,
  type FrequencyView,
} from '../../shared/api/frequenciesApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'

interface WeeklyRuleFormValue {
  enabled: boolean
  cutoffTime: string
  leadTimeDays: string
}

interface FrequencyFormValues {
  code: string
  name: string
  description: string
  /** Fixed length 7, index 0 = Monday (dayOfWeek 1) .. index 6 = Sunday (dayOfWeek 7). */
  weeklyRules: WeeklyRuleFormValue[]
}

interface FrequencyFormModalProps {
  companyId: string
  /** `null` creates a new frequency; otherwise the form edits this one. */
  frequency: FrequencyView | null
  onClose: () => void
  onSaved: () => void
}

const DAY_LABELS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

const KNOWN_TOP_LEVEL_FIELDS = new Set<keyof FrequencyFormValues>(['code', 'name', 'description'])

function buildDefaultWeeklyRules(frequency: FrequencyView | null): WeeklyRuleFormValue[] {
  return DAY_LABELS.map((_, index) => {
    const dayOfWeek = index + 1
    const existing = frequency?.weeklyRules.find((rule) => rule.dayOfWeek === dayOfWeek)
    return {
      enabled: existing?.enabled ?? false,
      cutoffTime: existing?.cutoffTime?.slice(0, 5) ?? '',
      leadTimeDays: existing?.leadTimeDays?.toString() ?? '',
    }
  })
}

/** Create and edit share one form. The weekly grid always renders all 7 days and sends all 7
 * rows on submit - see `FrequencyRequest`'s doc comment for why that is not a partial update. */
export function FrequencyFormModal({ companyId, frequency, onClose, onSaved }: FrequencyFormModalProps) {
  const isEdit = frequency !== null
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FrequencyFormValues>({
    defaultValues: {
      code: frequency?.code ?? '',
      name: frequency?.name ?? '',
      description: frequency?.description ?? '',
      weeklyRules: buildDefaultWeeklyRules(frequency),
    },
  })

  async function onSubmit(values: FrequencyFormValues) {
    setFormError(null)
    const request: FrequencyRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      description: values.description.trim() || null,
      weeklyRules: values.weeklyRules.map((rule, index) => ({
        dayOfWeek: index + 1,
        enabled: rule.enabled,
        cutoffTime: rule.cutoffTime.trim() === '' ? null : `${rule.cutoffTime}:00`,
        leadTimeDays: rule.leadTimeDays.trim() === '' ? null : Number(rule.leadTimeDays),
      })),
    }

    try {
      if (isEdit) {
        await updateFrequency(companyId, frequency.id, request)
      } else {
        await createFrequency(companyId, request)
      }
      onSaved()
    } catch (error) {
      const apiError = error as ApiError
      if (apiError.fieldErrors.length > 0) {
        const unmatched: string[] = []
        for (const fieldError of apiError.fieldErrors) {
          if (KNOWN_TOP_LEVEL_FIELDS.has(fieldError.field as keyof FrequencyFormValues)) {
            setError(fieldError.field as keyof FrequencyFormValues, { message: fieldError.message })
          } else {
            unmatched.push(fieldError.message)
          }
        }
        setFormError(unmatched.length > 0 ? unmatched.join(' ') : 'Please correct the highlighted fields.')
      } else {
        setFormError(describeApiError(apiError))
      }
    }
  }

  return (
    <div
      className="modal d-block"
      tabIndex={-1}
      role="dialog"
      aria-modal="true"
      onKeyDown={(event) => {
        if (event.key === 'Escape') onClose()
      }}
    >
      <div className="modal-dialog modal-lg" role="document">
        <div className="modal-content">
          <form onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <div className="modal-header">
              <h5 className="modal-title">{isEdit ? 'Edit frequency' : 'New frequency'}</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              {formError && (
                <div className="alert alert-danger py-2 small" role="alert">
                  {formError}
                </div>
              )}
              <div className="row">
                <div className="col-md-4">
                  <FormField label="Code" htmlFor="frequency-code" error={errors.code?.message} required>
                    <input
                      id="frequency-code"
                      className={`form-control${errors.code ? ' is-invalid' : ''}`}
                      {...register('code', {
                        required: 'Code is required',
                        maxLength: { value: 32, message: 'Must be 32 characters or fewer' },
                        pattern: {
                          value: /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/,
                          message: 'Letters, digits, underscore or hyphen only',
                        },
                      })}
                    />
                  </FormField>
                </div>
                <div className="col-md-8">
                  <FormField label="Name" htmlFor="frequency-name" error={errors.name?.message} required>
                    <input
                      id="frequency-name"
                      className={`form-control${errors.name ? ' is-invalid' : ''}`}
                      {...register('name', {
                        required: 'Name is required',
                        maxLength: { value: 200, message: 'Must be 200 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
              </div>
              <FormField label="Description" htmlFor="frequency-description" error={errors.description?.message}>
                <input
                  id="frequency-description"
                  className={`form-control${errors.description ? ' is-invalid' : ''}`}
                  {...register('description', { maxLength: { value: 1000, message: 'Must be 1000 characters or fewer' } })}
                />
              </FormField>

              <label className="form-label mt-2">Weekly cadence</label>
              <div className="table-responsive">
                <table className="table table-sm align-middle mb-2">
                  <thead>
                    <tr>
                      <th scope="col">Day</th>
                      <th scope="col">Service day</th>
                      <th scope="col">Cutoff time</th>
                      <th scope="col">Lead time (days)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {DAY_LABELS.map((label, index) => (
                      <tr key={label}>
                        <td>
                          <label htmlFor={`frequency-day-${index}-enabled`}>{label}</label>
                        </td>
                        <td>
                          <input
                            id={`frequency-day-${index}-enabled`}
                            type="checkbox"
                            className="form-check-input"
                            {...register(`weeklyRules.${index}.enabled`)}
                          />
                        </td>
                        <td>
                          <input
                            aria-label={`${label} cutoff time`}
                            type="time"
                            className="form-control form-control-sm"
                            {...register(`weeklyRules.${index}.cutoffTime`)}
                          />
                        </td>
                        <td>
                          <input
                            aria-label={`${label} lead time in days`}
                            type="number"
                            min={0}
                            className="form-control form-control-sm"
                            {...register(`weeklyRules.${index}.leadTimeDays`, {
                              min: { value: 0, message: 'must be zero or greater' },
                            })}
                          />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="alert alert-secondary small mb-0" role="note">
                Date exceptions (extra pickups, blackouts) are supported by the backend but do
                not yet have an editor in this release. They are managed one date at a time
                through the frequency API and will get a dedicated screen in a later step.
              </div>
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-outline-secondary" onClick={onClose}>
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={isSubmitting}>
                {isSubmitting ? 'Saving...' : 'Save'}
              </button>
            </div>
          </form>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </div>
  )
}
