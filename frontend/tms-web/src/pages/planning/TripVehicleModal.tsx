import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchVehicles } from '../../shared/api/vehiclesApi'
import { updateTripVehicle, type TripDetailView, type TripVehicleRequest, type TripView } from '../../shared/api/planningApi'
import { describePlanningError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsModal } from '../../shared/ui/components/TmsModal'

const FORM_ID = 'trip-vehicle-form'

interface TripVehicleModalProps {
  companyId: string
  trip: TripView
  onClose: () => void
  onUpdated: (detail: TripDetailView) => void
}

interface TripVehicleFormValues {
  vehicleId: string
  plannedDepartureAt: string
}

function toLocalInputValue(isoValue: string | null): string {
  if (!isoValue) return ''
  const date = new Date(isoValue)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

interface VehicleOption {
  id: string
  code: string
  licensePlate: string
}

/** Prepends the trip's currently assigned vehicle if it fell out of the active-only list fetched
 * for the dropdown - the same pattern `OrderFormModal`/`RouteFormModal` use, and load-bearing
 * here too: without it, react-hook-form's uncontrolled `defaultValue` is applied before the async
 * vehicle list resolves, so the select would silently reset to "Select a vehicle". */
function withCurrentVehicle(options: VehicleOption[], trip: TripView): VehicleOption[] {
  if (!trip.vehicleId || options.some((option) => option.id === trip.vehicleId)) {
    return options
  }
  return [{ id: trip.vehicleId, code: trip.vehicleCode ?? trip.vehicleId, licensePlate: trip.vehicleLicensePlate ?? '' }, ...options]
}

/** Sets or swaps a draft trip's vehicle. The backend revalidates everything already assigned to
 * the trip against the new vehicle's capacity and refuses a downgrade that no longer fits -
 * `docs/domain/CAPACITY_MODEL.md`, "Where the checks fire". Sends the trip's `version` so a
 * vehicle change made from a board that has since been filled by someone else is refused rather
 * than silently applied. */
export function TripVehicleModal({ companyId, trip, onClose, onUpdated }: TripVehicleModalProps) {
  const { t } = useTranslation('planning')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const [formError, setFormError] = useState<string | null>(null)

  const vehiclesQuery = useQuery({
    queryKey: ['vehicles-for-trip-vehicle-form', companyId],
    queryFn: ({ signal }) => fetchVehicles({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const vehicles = withCurrentVehicle(vehiclesQuery.data?.content ?? [], trip)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<TripVehicleFormValues>({
    defaultValues: {
      vehicleId: trip.vehicleId ?? '',
      plannedDepartureAt: toLocalInputValue(trip.plannedDepartureAt),
    },
  })

  async function onSubmit(values: TripVehicleFormValues) {
    setFormError(null)
    const request: TripVehicleRequest = {
      vehicleId: values.vehicleId,
      plannedDepartureAt: values.plannedDepartureAt ? new Date(values.plannedDepartureAt).toISOString() : null,
      version: trip.version,
    }

    try {
      const detail = await updateTripVehicle(companyId, trip.id, request)
      onUpdated(detail)
    } catch (error) {
      setFormError(describePlanningError(error as ApiError))
    }
  }

  return (
    <TmsModal
      open
      title={t('trip.form.vehicleTitle', { number: trip.tripNumber })}
      onClose={onClose}
      closeOnEscape={!isSubmitting}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={isSubmitting}>
            {tc('actions.cancel')}
          </button>
          <button type="submit" form={FORM_ID} className="btn btn-primary" disabled={isSubmitting}>
            {isSubmitting ? tc('actions.saving') : t('trip.form.submitVehicle')}
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

        <FormField label={t('trip.form.vehicle')} htmlFor="trip-vehicle-select" error={errors.vehicleId?.message} required>
          <select
            id="trip-vehicle-select"
            className={`form-select${errors.vehicleId ? ' is-invalid' : ''}`}
            {...register('vehicleId', { required: tv('required') })}
          >
            <option value="">{t('trip.form.selectVehicle')}</option>
            {vehicles.map((vehicle) => (
              <option key={vehicle.id} value={vehicle.id}>
                {vehicle.code} — {vehicle.licensePlate}
              </option>
            ))}
          </select>
        </FormField>

        <FormField label={t('trip.form.departure')} htmlFor="trip-vehicle-departure">
          <input
            id="trip-vehicle-departure"
            type="datetime-local"
            className="form-control"
            {...register('plannedDepartureAt')}
          />
        </FormField>
      </form>
    </TmsModal>
  )
}
