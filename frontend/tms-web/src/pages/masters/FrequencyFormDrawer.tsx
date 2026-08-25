import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import {
  createFrequency,
  updateFrequency,
  type FrequencyRequest,
  type FrequencyView,
} from '../../shared/api/frequenciesApi'
import type { CommonKey } from '../../shared/i18n/keys'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'
import { FrequencyExceptionsPanel } from './FrequencyExceptionsPanel'

const FORM_ID = 'frequency-form'

/** Matches the backend's `code` constraint; kept next to the field it validates. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/

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

interface FrequencyFormDrawerProps {
  companyId: string
  /** `null` creates a new frequency; otherwise the form edits this one. */
  frequency: FrequencyView | null
  onClose: () => void
  onSaved: () => void
}

/** Index 0 is Monday, matching `dayOfWeek` 1 in the request. Keys, not text: the grid must
 * follow the UI language like everything else. */
const DAY_KEYS: CommonKey[] = [
  'weekdays.monday',
  'weekdays.tuesday',
  'weekdays.wednesday',
  'weekdays.thursday',
  'weekdays.friday',
  'weekdays.saturday',
  'weekdays.sunday',
]

const KNOWN_TOP_LEVEL_FIELDS = new Set<keyof FrequencyFormValues>(['code', 'name', 'description'])

function buildDefaultWeeklyRules(frequency: FrequencyView | null): WeeklyRuleFormValue[] {
  return DAY_KEYS.map((_unused, index) => {
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
export function FrequencyFormDrawer({ companyId, frequency, onClose, onSaved }: FrequencyFormDrawerProps) {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const isEdit = frequency !== null
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
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
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_TOP_LEVEL_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('frequencies.form.edit') : t('frequencies.form.create')}
      subtitle={t('frequencies.form.subtitle')}
      size="lg"
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
      <form id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && (
          <div className="alert alert-danger py-2 small" role="alert">
            {formError}
          </div>
        )}

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.identification')}</legend>
          <div className="row">
            <div className="col-12 col-sm-4">
              <FormField label={tc('columns.code')} htmlFor="frequency-code" error={errors.code?.message} required>
                <input
                  id="frequency-code"
                  className={`form-control${errors.code ? ' is-invalid' : ''}`}
                  {...register('code', {
                    required: tv('required'),
                    maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                    pattern: { value: CODE_PATTERN, message: tv('codePattern') },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-8">
              <FormField label={tc('columns.name')} htmlFor="frequency-name" error={errors.name?.message} required>
                <input
                  id="frequency-name"
                  className={`form-control${errors.name ? ' is-invalid' : ''}`}
                  {...register('name', {
                    required: tv('required'),
                    maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12">
              <FormField
                label={tc('columns.description')}
                htmlFor="frequency-description"
                error={errors.description?.message}
              >
                <input
                  id="frequency-description"
                  className={`form-control${errors.description ? ' is-invalid' : ''}`}
                  {...register('description', { maxLength: { value: 1000, message: tv('maxLength', { count: 1000 }) } })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{t('frequencies.form.weeklyCadence')}</legend>

          <div className="tms-table-scroll">
            <table className="table table-sm align-middle mb-2">
              <thead>
                <tr>
                  <th scope="col">{t('frequencies.form.day')}</th>
                  <th scope="col">{t('frequencies.form.serviceDay')}</th>
                  <th scope="col">{t('frequencies.form.cutoffTime')}</th>
                  <th scope="col">{t('frequencies.form.leadTimeDays')}</th>
                </tr>
              </thead>
              <tbody>
                {DAY_KEYS.map((dayKey, index) => {
                  const day = tc(dayKey)
                  return (
                    <tr key={dayKey}>
                      <td>
                        <label htmlFor={`frequency-day-${index}-enabled`}>{day}</label>
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
                          aria-label={t('frequencies.form.cutoffAria', { day })}
                          type="time"
                          className="form-control form-control-sm"
                          {...register(`weeklyRules.${index}.cutoffTime`)}
                        />
                      </td>
                      <td>
                        <input
                          aria-label={t('frequencies.form.leadTimeAria', { day })}
                          type="number"
                          min={0}
                          className="form-control form-control-sm"
                          {...register(`weeklyRules.${index}.leadTimeDays`, {
                            min: { value: 0, message: tv('nonNegative') },
                          })}
                        />
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{t('frequencies.form.exceptions')}</legend>
          {frequency ? (
            <FrequencyExceptionsPanel companyId={companyId} frequencyId={frequency.id} canManage />
          ) : (
            // A sub-resource needs something to hang from. Saying so beats a panel that looks
            // broken because every call it makes would 404.
            <p className="text-body-secondary small mb-0">{t('frequencies.form.saveFirstForExceptions')}</p>
          )}
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
