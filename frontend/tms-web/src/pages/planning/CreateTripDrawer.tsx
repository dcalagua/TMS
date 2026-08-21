import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchVehicles } from '../../shared/api/vehiclesApi'
import { createTrip, type TripCreateRequest, type TripDetailView } from '../../shared/api/planningApi'
import { describePlanningError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'create-trip-form'

interface CreateTripDrawerProps {
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
export function CreateTripDrawer({ companyId, runId, runVersion, onClose, onCreated }: CreateTripDrawerProps) {
  const { t } = useTranslation('planning')
  const { t: tc } = useTranslation('common')
  const [formError, setFormError] = useState<string | null>(null)

  const vehiclesQuery = useQuery({
    queryKey: ['vehicles-for-trip-form', companyId],
    queryFn: ({ signal }) => fetchVehicles({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const vehicles = vehiclesQuery.data?.content ?? []

  const {
    register,
    control,
    handleSubmit,
    formState: { isDirty, isSubmitting },
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
    <TmsDrawer
      open
      title={t('trip.form.create')}
      subtitle={t('trip.form.subtitle')}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      // A stray Escape or backdrop click must not abandon a submit already in flight.
      closeOnEscape={!isSubmitting}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={isSubmitting}>
            {tc('actions.cancel')}
          </button>
          <button type="submit" form={FORM_ID} className="btn btn-primary" disabled={isSubmitting}>
            {isSubmitting ? t('trip.form.creating') : t('trip.form.submitCreate')}
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

        <FormField label={t('trip.form.vehicle')} htmlFor="trip-vehicle">
          {/* Controller rather than `register`: the control is a button plus a listbox, so
              there is no native change event for react-hook-form to hook into. */}
          <Controller
            control={control}
            name="vehicleId"
            render={({ field }) => (
              <Select
                id="trip-vehicle"
                value={field.value}
                onChange={(next) => field.onChange(next)}
                options={[
                  { value: '', label: t('trip.form.decideLater') },
                  ...vehicles.map((vehicle) => ({ value: vehicle.id, label: `${vehicle.code} — ${vehicle.licensePlate}` })),
                ]}
              />
            )}
          />
        </FormField>

        <FormField label={t('trip.form.departure')} htmlFor="trip-departure">
          <input
            id="trip-departure"
            type="datetime-local"
            className="form-control"
            {...register('plannedDepartureAt')}
          />
        </FormField>
      </form>
    </TmsDrawer>
  )
}
