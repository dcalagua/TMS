import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchVehicles } from '../../shared/api/vehiclesApi'
import { createTrip, type TripCreateRequest, type TripDetailView } from '../../shared/api/planningApi'
import { describePlanningError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'

interface CreateTripModalProps {
  companyId: string
  runId: string
  runVersion: number
  onClose: () => void
  onCreated: (detail: TripDetailView) => void
}

interface CreateTripFormValues {
  vehicleId: string
  plannedDepartureAt: string
}

/** Creates a trip inside a draft run. Both the vehicle and the departure are optional - a
 * planner routinely sketches "trip 3" before deciding which truck runs it
 * (`TripCreateRequest`'s javadoc). Sends the *run's* version, since trip creation is a run-level
 * write that fails loudly if the run was confirmed or cancelled since it was loaded. */
export function CreateTripModal({ companyId, runId, runVersion, onClose, onCreated }: CreateTripModalProps) {
  const [formError, setFormError] = useState<string | null>(null)

  const vehiclesQuery = useQuery({
    queryKey: ['vehicles-for-trip-form', companyId],
    queryFn: ({ signal }) => fetchVehicles({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const vehicles = vehiclesQuery.data?.content ?? []

  const {
    register,
    handleSubmit,
    formState: { isSubmitting },
  } = useForm<CreateTripFormValues>({ defaultValues: { vehicleId: '', plannedDepartureAt: '' } })

  async function onSubmit(values: CreateTripFormValues) {
    setFormError(null)
    const request: TripCreateRequest = {
      vehicleId: values.vehicleId || null,
      plannedDepartureAt: values.plannedDepartureAt ? new Date(values.plannedDepartureAt).toISOString() : null,
      version: runVersion,
    }

    try {
      const detail = await createTrip(companyId, runId, request)
      onCreated(detail)
    } catch (error) {
      setFormError(describePlanningError(error as ApiError))
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
              <h5 className="modal-title">New trip</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              {formError && (
                <div className="alert alert-danger py-2 small" role="alert">
                  {formError}
                </div>
              )}
              <FormField label="Vehicle" htmlFor="trip-vehicle">
                <select id="trip-vehicle" className="form-select" {...register('vehicleId')}>
                  <option value="">Decide later</option>
                  {vehicles.map((vehicle) => (
                    <option key={vehicle.id} value={vehicle.id}>
                      {vehicle.code} — {vehicle.licensePlate}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField label="Planned departure" htmlFor="trip-departure">
                <input id="trip-departure" type="datetime-local" className="form-control" {...register('plannedDepartureAt')} />
              </FormField>
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-outline-secondary" onClick={onClose}>
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={isSubmitting}>
                {isSubmitting ? 'Creating...' : 'Create trip'}
              </button>
            </div>
          </form>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </div>
  )
}
