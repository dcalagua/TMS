import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { fetchDrivers, type DriverView } from '../../shared/api/driversApi'
import type { ApiError } from '../../shared/api/httpClient'
import {
  updateTripDriver,
  type TripDetailView,
  type TripDriverRequest,
  type TripView,
} from '../../shared/api/planningApi'
import { describePlanningError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'trip-driver-form'

interface TripDriverDrawerProps {
  companyId: string
  trip: TripView
  onClose: () => void
  onUpdated: (detail: TripDetailView) => void
}

interface TripDriverFormValues {
  driverId: string
}

interface DriverOption {
  id: string
  label: string
}

/** Keeps the trip's current driver in the list even after they are deactivated - the same
 * `withCurrent*` pattern `TripVehicleDrawer` uses, and load-bearing for the same reason: without
 * it the select silently resets to "no driver" while the list is still loading. */
function withCurrentDriver(options: DriverOption[], trip: TripView): DriverOption[] {
  if (!trip.driverId || options.some((option) => option.id === trip.driverId)) {
    return options
  }
  return [{ id: trip.driverId, label: trip.driverName ?? trip.driverCode ?? trip.driverId }, ...options]
}

/**
 * Names, swaps or clears a trip's driver.
 *
 * <p>Available up to departure rather than only while the trip is a draft: a driver calling in
 * sick on a shipment confirmed last night is the ordinary case, and nothing the plan was
 * *validated against* changes when the person at the wheel does. The server owns that rule and
 * refuses the swap once the vehicle has left.
 *
 * <p>The list is fetched active-only, and each option carries its licence warning in the label.
 * The refusal itself is the server's - an expired licence is rejected by the endpoint, not hidden
 * here - but a planner should be able to see it before they pick rather than after.
 */
export function TripDriverDrawer({ companyId, trip, onClose, onUpdated }: TripDriverDrawerProps) {
  const { t } = useTranslation('planning')
  const { t: tc } = useTranslation('common')
  const [formError, setFormError] = useState<string | null>(null)

  const driversQuery = useQuery({
    queryKey: ['drivers-for-trip-driver-form', companyId],
    queryFn: ({ signal }) => fetchDrivers({ companyId, size: 200, active: true, signal }),
  })

  function optionLabel(driver: DriverView): string {
    const suffix =
      driver.licenseStatus === 'EXPIRED'
        ? ` — ${t('trip.form.licenseExpiredOption')}`
        : driver.licenseStatus === 'EXPIRING_SOON'
          ? ` — ${t('trip.form.licenseExpiringOption')}`
          : ''
    return `${driver.fullName} (${driver.code})${suffix}`
  }

  const drivers = withCurrentDriver(
    (driversQuery.data?.content ?? []).map((driver) => ({ id: driver.id, label: optionLabel(driver) })),
    trip,
  )

  const {
    control,
    handleSubmit,
    formState: { isDirty, isSubmitting },
  } = useForm<TripDriverFormValues>({
    defaultValues: { driverId: trip.driverId ?? '' },
  })

  async function onSubmit(values: TripDriverFormValues) {
    setFormError(null)
    const request: TripDriverRequest = {
      // An empty select is "no driver", which the API takes as null and treats as a real
      // instruction: it releases the person for another trip that day.
      driverId: values.driverId === '' ? null : values.driverId,
      version: trip.version,
    }

    try {
      onUpdated(await updateTripDriver(companyId, trip.id, request))
    } catch (error) {
      setFormError(describePlanningError(error as ApiError))
    }
  }

  return (
    <TmsDrawer
      open
      title={t('trip.form.driverTitle', { number: trip.tripNumber })}
      subtitle={t('trip.form.driverSubtitle')}
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
            {isSubmitting ? tc('actions.saving') : t('trip.form.submitDriver')}
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

        <FormField label={t('trip.form.driver')} htmlFor="trip-driver-select">
          <Controller
            control={control}
            name="driverId"
            render={({ field }) => (
              <Select
                id="trip-driver-select"
                value={field.value}
                onChange={(next) => field.onChange(next)}
                options={[
                  { value: '', label: t('trip.form.noDriver') },
                  ...drivers.map((driver) => ({ value: driver.id, label: driver.label })),
                ]}
              />
            )}
          />
        </FormField>
      </form>
    </TmsDrawer>
  )
}
