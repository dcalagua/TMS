import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useFieldArray, useForm } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchOrigins } from '../../shared/api/originsApi'
import { fetchZones } from '../../shared/api/zonesApi'
import { fetchFrequencies } from '../../shared/api/frequenciesApi'
import { fetchDestinations } from '../../shared/api/destinationsApi'
import {
  createRoute,
  fetchRoute,
  updateRoute,
  type RouteDetailView,
  type RouteRequest,
} from '../../shared/api/routesApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'
import { LoadingState } from '../../shared/ui/components/LoadingState'

interface SelectOption {
  id: string
  code: string
  name: string
}

interface RouteFormValues {
  code: string
  name: string
  originId: string
  zoneId: string
  frequencyId: string
  referenceDistanceKm: string
  referenceDurationMinutes: string
  stops: { destinationId: string }[]
}

interface RouteFormModalProps {
  companyId: string
  /** `null` creates a new route; otherwise the modal loads and edits this route's full detail
   * (including its ordered stops) - the list row alone (`RouteView`) does not carry them, by
   * design (see `RouteView.java`'s class comment on avoiding N+1 on the list). */
  routeId: string | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof RouteFormValues>([
  'code', 'name', 'originId', 'zoneId', 'frequencyId', 'referenceDistanceKm', 'referenceDurationMinutes',
])

/** Prepends the currently assigned option if it fell out of the active-only list fetched for the
 * dropdown - the same "deactivating does not silently delete route history" invariant the stop
 * editor below also protects, applied to origin/zone/frequency. */
function withCurrentValue(options: SelectOption[], id: string | null, code: string | null, name: string | null) {
  if (!id || options.some((option) => option.id === id)) {
    return options
  }
  return [{ id, code: code ?? id, name: name ?? code ?? id }, ...options]
}

export function RouteFormModal({ companyId, routeId, onClose, onSaved }: RouteFormModalProps) {
  const routeQuery = useQuery({
    queryKey: ['route', companyId, routeId],
    queryFn: ({ signal }) => fetchRoute(companyId, routeId as string, signal),
    enabled: routeId !== null,
  })

  if (routeId !== null && !routeQuery.data) {
    return (
      <div className="modal d-block" tabIndex={-1} role="dialog" aria-modal="true">
        <div className="modal-dialog modal-lg" role="document">
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">Edit route</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              {routeQuery.isError ? (
                <div className="alert alert-danger py-2 small" role="alert">
                  {describeApiError(routeQuery.error as ApiError)}
                </div>
              ) : (
                <LoadingState label="Loading route..." />
              )}
            </div>
          </div>
        </div>
        <div className="modal-backdrop show" />
      </div>
    )
  }

  return <RouteForm companyId={companyId} route={routeQuery.data ?? null} onClose={onClose} onSaved={onSaved} />
}

function RouteForm({
  companyId, route, onClose, onSaved,
}: {
  companyId: string
  route: RouteDetailView | null
  onClose: () => void
  onSaved: () => void
}) {
  const isEdit = route !== null
  const [formError, setFormError] = useState<string | null>(null)
  const [stopToAdd, setStopToAdd] = useState('')

  const originsQuery = useQuery({
    queryKey: ['origins-for-route-form', companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const zonesQuery = useQuery({
    queryKey: ['zones-for-route-form', companyId],
    queryFn: ({ signal }) => fetchZones({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const frequenciesQuery = useQuery({
    queryKey: ['frequencies-for-route-form', companyId],
    queryFn: ({ signal }) => fetchFrequencies({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const destinationsQuery = useQuery({
    queryKey: ['destinations-for-route-form', companyId],
    queryFn: ({ signal }) => fetchDestinations({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })

  const originOptions = withCurrentValue(
    originsQuery.data?.content ?? [], route?.originId ?? null, route?.originCode ?? null, route?.originName ?? null,
  )
  const zoneOptions = withCurrentValue(
    zonesQuery.data?.content ?? [], route?.zoneId ?? null, route?.zoneCode ?? null, route?.zoneName ?? null,
  )
  const frequencyOptions = withCurrentValue(
    frequenciesQuery.data?.content ?? [], route?.frequencyId ?? null, route?.frequencyCode ?? null,
    route?.frequencyName ?? null,
  )
  const availableDestinations = useMemo(() => destinationsQuery.data?.content ?? [], [destinationsQuery.data])

  /** Every stop's code/name, preferring the route's own (possibly-deactivated) destination data
   * over the active-only fetch, so an existing stop always renders correctly even if the
   * destination behind it was deactivated since - see the class comment above. */
  const destinationLookup = useMemo(() => {
    const map = new Map<string, { code: string; name: string }>()
    for (const destination of availableDestinations) {
      map.set(destination.id, { code: destination.code, name: destination.name })
    }
    for (const stop of route?.stops ?? []) {
      map.set(stop.destinationId, {
        code: stop.destinationCode ?? stop.destinationId,
        name: stop.destinationName ?? '',
      })
    }
    return map
  }, [availableDestinations, route])

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RouteFormValues>({
    defaultValues: {
      code: route?.code ?? '',
      name: route?.name ?? '',
      originId: route?.originId ?? '',
      zoneId: route?.zoneId ?? '',
      frequencyId: route?.frequencyId ?? '',
      referenceDistanceKm: route?.referenceDistanceKm?.toString() ?? '',
      referenceDurationMinutes: route?.referenceDurationMinutes?.toString() ?? '',
      stops: (route?.stops ?? []).map((stop) => ({ destinationId: stop.destinationId })),
    },
  })
  const { fields, append, remove, move } = useFieldArray({ control, name: 'stops' })

  const addableDestinations = availableDestinations.filter(
    (destination) => !fields.some((field) => field.destinationId === destination.id),
  )

  function addStop() {
    if (stopToAdd === '') return
    append({ destinationId: stopToAdd })
    setStopToAdd('')
  }

  async function onSubmit(values: RouteFormValues) {
    setFormError(null)
    if (values.stops.length === 0) {
      setFormError('Add at least one destination stop.')
      return
    }

    const request: RouteRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      originId: values.originId,
      zoneId: values.zoneId || null,
      frequencyId: values.frequencyId || null,
      referenceDistanceKm: values.referenceDistanceKm.trim() === '' ? null : Number(values.referenceDistanceKm),
      referenceDurationMinutes:
        values.referenceDurationMinutes.trim() === '' ? null : Number(values.referenceDurationMinutes),
      destinationIds: values.stops.map((stop) => stop.destinationId),
    }

    try {
      if (isEdit) {
        await updateRoute(companyId, route.id, request)
      } else {
        await createRoute(companyId, request)
      }
      onSaved()
    } catch (error) {
      const apiError = error as ApiError
      if (apiError.fieldErrors.length > 0) {
        const unmatched: string[] = []
        for (const fieldError of apiError.fieldErrors) {
          if (KNOWN_FIELDS.has(fieldError.field as keyof RouteFormValues)) {
            setError(fieldError.field as keyof RouteFormValues, { message: fieldError.message })
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
              <h5 className="modal-title">{isEdit ? 'Edit route' : 'New route'}</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              <div className="alert alert-secondary small py-2" role="note">
                A Master Route is a reusable planned corridor, not a calculated Trip route: it has
                no live position or optimizer output, just an origin and an ordered list of stops
                a planner sets up once.
              </div>
              {formError && (
                <div className="alert alert-danger py-2 small" role="alert">
                  {formError}
                </div>
              )}
              <div className="row">
                <div className="col-md-3">
                  <FormField label="Code" htmlFor="route-code" error={errors.code?.message} required>
                    <input
                      id="route-code"
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
                <div className="col-md-9">
                  <FormField label="Name" htmlFor="route-name" error={errors.name?.message} required>
                    <input
                      id="route-name"
                      className={`form-control${errors.name ? ' is-invalid' : ''}`}
                      {...register('name', {
                        required: 'Name is required',
                        maxLength: { value: 200, message: 'Must be 200 characters or fewer' },
                      })}
                    />
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-4">
                  <FormField label="Origin" htmlFor="route-origin" error={errors.originId?.message} required>
                    <select
                      id="route-origin"
                      className={`form-select${errors.originId ? ' is-invalid' : ''}`}
                      {...register('originId', { required: 'Origin is required' })}
                    >
                      <option value="">Select an origin</option>
                      {originOptions.map((origin) => (
                        <option key={origin.id} value={origin.id}>
                          {origin.name}
                        </option>
                      ))}
                    </select>
                  </FormField>
                </div>
                <div className="col-md-4">
                  <FormField label="Zone" htmlFor="route-zone" error={errors.zoneId?.message}>
                    <select id="route-zone" className="form-select" {...register('zoneId')}>
                      <option value="">No zone</option>
                      {zoneOptions.map((zone) => (
                        <option key={zone.id} value={zone.id}>
                          {zone.name}
                        </option>
                      ))}
                    </select>
                  </FormField>
                </div>
                <div className="col-md-4">
                  <FormField label="Frequency" htmlFor="route-frequency" error={errors.frequencyId?.message}>
                    <select id="route-frequency" className="form-select" {...register('frequencyId')}>
                      <option value="">No frequency</option>
                      {frequencyOptions.map((frequency) => (
                        <option key={frequency.id} value={frequency.id}>
                          {frequency.name}
                        </option>
                      ))}
                    </select>
                  </FormField>
                </div>
              </div>
              <div className="row">
                <div className="col-md-6">
                  <FormField
                    label="Reference distance (km)"
                    htmlFor="route-distance"
                    error={errors.referenceDistanceKm?.message}
                  >
                    <input
                      id="route-distance"
                      type="number"
                      min={0}
                      step="0.01"
                      className={`form-control${errors.referenceDistanceKm ? ' is-invalid' : ''}`}
                      {...register('referenceDistanceKm', { min: { value: 0, message: 'Must be zero or greater' } })}
                    />
                  </FormField>
                </div>
                <div className="col-md-6">
                  <FormField
                    label="Reference duration (min)"
                    htmlFor="route-duration"
                    error={errors.referenceDurationMinutes?.message}
                  >
                    <input
                      id="route-duration"
                      type="number"
                      min={0}
                      className={`form-control${errors.referenceDurationMinutes ? ' is-invalid' : ''}`}
                      {...register('referenceDurationMinutes', {
                        min: { value: 0, message: 'Must be zero or greater' },
                      })}
                    />
                  </FormField>
                </div>
              </div>

              <label className="form-label mt-2">Destination stops, in order</label>
              {fields.length === 0 && <p className="text-body-secondary small">No stops added yet.</p>}
              {fields.length > 0 && (
                <ol className="list-group list-group-numbered mb-2">
                  {fields.map((field, index) => {
                    const destination = destinationLookup.get(field.destinationId)
                    return (
                      <li key={field.id} className="list-group-item d-flex justify-content-between align-items-center">
                        <span>
                          {destination ? `${destination.code} — ${destination.name}` : field.destinationId}
                        </span>
                        <div className="btn-group btn-group-sm">
                          <button
                            type="button"
                            className="btn btn-outline-secondary"
                            aria-label={`Move stop ${index + 1} up`}
                            disabled={index === 0}
                            onClick={() => move(index, index - 1)}
                          >
                            ↑
                          </button>
                          <button
                            type="button"
                            className="btn btn-outline-secondary"
                            aria-label={`Move stop ${index + 1} down`}
                            disabled={index === fields.length - 1}
                            onClick={() => move(index, index + 1)}
                          >
                            ↓
                          </button>
                          <button
                            type="button"
                            className="btn btn-outline-danger"
                            aria-label={`Remove stop ${index + 1}`}
                            onClick={() => remove(index)}
                          >
                            Remove
                          </button>
                        </div>
                      </li>
                    )
                  })}
                </ol>
              )}
              <div className="input-group input-group-sm">
                <select
                  className="form-select"
                  aria-label="Destination to add"
                  value={stopToAdd}
                  onChange={(event) => setStopToAdd(event.target.value)}
                >
                  <option value="">Select a destination to add</option>
                  {addableDestinations.map((destination) => (
                    <option key={destination.id} value={destination.id}>
                      {destination.code} — {destination.name}
                    </option>
                  ))}
                </select>
                <button type="button" className="btn btn-outline-primary" onClick={addStop} disabled={stopToAdd === ''}>
                  Add stop
                </button>
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
