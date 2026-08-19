import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchOrigins } from '../../shared/api/originsApi'
import { createPlanningRun, type PlanningRunDetailView, type PlanningRunRequest } from '../../shared/api/planningApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'

interface PlanningRunFormValues {
  originId: string
  planningDate: string
  notes: string
}

interface PlanningRunFormModalProps {
  companyId: string
  onClose: () => void
  onCreated: (run: PlanningRunDetailView) => void
}

const KNOWN_FIELDS = new Set<keyof PlanningRunFormValues>(['originId', 'planningDate', 'notes'])

/** Opens a new manual planning run. There is no edit form: a run's origin and date are fixed for
 * its lifetime (`docs/domain/PLANNING_MANUAL_V1.md` section 2) - only its trips and assignments
 * change, which the board (`PlanningBoardPage`) handles, not this modal. */
export function PlanningRunFormModal({ companyId, onClose, onCreated }: PlanningRunFormModalProps) {
  const [formError, setFormError] = useState<string | null>(null)

  const originsQuery = useQuery({
    queryKey: ['origins-for-planning-run-form', companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const origins = originsQuery.data?.content ?? []

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
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
      const apiError = error as ApiError
      if (apiError.fieldErrors.length > 0) {
        const unmatched: string[] = []
        for (const fieldError of apiError.fieldErrors) {
          if (KNOWN_FIELDS.has(fieldError.field as keyof PlanningRunFormValues)) {
            setError(fieldError.field as keyof PlanningRunFormValues, { message: fieldError.message })
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
      <div className="modal-dialog" role="document">
        <div className="modal-content">
          <form onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <div className="modal-header">
              <h5 className="modal-title">New planning run</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              {formError && (
                <div className="alert alert-danger py-2 small" role="alert">
                  {formError}
                </div>
              )}
              <FormField label="Origin" htmlFor="run-origin" error={errors.originId?.message} required>
                <select
                  id="run-origin"
                  className={`form-select${errors.originId ? ' is-invalid' : ''}`}
                  {...register('originId', { required: 'Origin is required' })}
                >
                  <option value="">Select an origin</option>
                  {origins.map((origin) => (
                    <option key={origin.id} value={origin.id}>
                      {origin.name}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField label="Planning date" htmlFor="run-date" error={errors.planningDate?.message} required>
                <input
                  id="run-date"
                  type="date"
                  className={`form-control${errors.planningDate ? ' is-invalid' : ''}`}
                  {...register('planningDate', { required: 'Planning date is required' })}
                />
              </FormField>
              <FormField label="Notes" htmlFor="run-notes" error={errors.notes?.message}>
                <textarea
                  id="run-notes"
                  className="form-control"
                  rows={3}
                  {...register('notes', { maxLength: { value: 2000, message: 'Must be at most 2000 characters' } })}
                />
              </FormField>
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-outline-secondary" onClick={onClose}>
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={isSubmitting}>
                {isSubmitting ? 'Creating...' : 'Create run'}
              </button>
            </div>
          </form>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </div>
  )
}
