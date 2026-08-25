import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Controller, useFieldArray, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
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
  type RouteStopRequest,
} from '../../shared/api/routesApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'
import { LoadingState } from '../../shared/ui/components/LoadingState'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'route-form'

/** Matches the backend's `code` constraint; kept next to the field it validates. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/

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
  /** `serviceTimeOverrideMinutes` empty means "inherit the location's" - see `toStopRequest`. */
  stops: { destinationId: string; serviceTimeOverrideMinutes: string }[]
}

/** Empty means inherit; `'0'` is a real override (a drop-and-go stop), so it must survive as 0. */
function toStopRequest(stop: RouteFormValues['stops'][number]): RouteStopRequest {
  const override = stop.serviceTimeOverrideMinutes.trim()
  return {
    destinationId: stop.destinationId,
    serviceTimeOverrideMinutes: override === '' ? null : Number(override),
  }
}

interface RouteFormDrawerProps {
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

export function RouteFormDrawer({ companyId, routeId, onClose, onSaved }: RouteFormDrawerProps) {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')

  const routeQuery = useQuery({
    queryKey: ['route', companyId, routeId],
    queryFn: ({ signal }) => fetchRoute(companyId, routeId as string, signal),
    enabled: routeId !== null,
  })

  // The edit form cannot render before the route's stops arrive, so the dialog opens with its
  // own loading state rather than flashing an empty form.
  if (routeId !== null && !routeQuery.data) {
    return (
      <TmsDrawer
        open
        title={t('routes.form.edit')}
        subtitle={t('routes.form.subtitle')}
        size="xl"
        onClose={onClose}
      >
        {routeQuery.isError ? (
          <div className="alert alert-danger py-2 small" role="alert">
            {describeApiError(routeQuery.error as ApiError)}
          </div>
        ) : (
          <LoadingState label={tc('loading.route')} />
        )}
      </TmsDrawer>
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
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
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

  /** Every stop's code/name and its location's own service time, preferring the route's own
   * (possibly-deactivated) destination data over the active-only fetch, so an existing stop
   * always renders correctly even if the destination behind it was deactivated since - see the
   * class comment above. `serviceTimeMinutes` is what the override field shows as its
   * placeholder: the value that applies when the operator leaves it empty. */
  const destinationLookup = useMemo(() => {
    const map = new Map<string, { code: string; name: string; serviceTimeMinutes: number | null }>()
    for (const destination of availableDestinations) {
      map.set(destination.id, {
        code: destination.code,
        name: destination.name,
        serviceTimeMinutes: destination.serviceTimeMinutes,
      })
    }
    for (const stop of route?.stops ?? []) {
      map.set(stop.destinationId, {
        code: stop.destinationCode ?? stop.destinationId,
        name: stop.destinationName ?? '',
        serviceTimeMinutes: stop.destinationServiceTimeMinutes,
      })
    }
    return map
  }, [availableDestinations, route])

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<RouteFormValues>({
    defaultValues: {
      code: route?.code ?? '',
      name: route?.name ?? '',
      originId: route?.originId ?? '',
      zoneId: route?.zoneId ?? '',
      frequencyId: route?.frequencyId ?? '',
      referenceDistanceKm: route?.referenceDistanceKm?.toString() ?? '',
      referenceDurationMinutes: route?.referenceDurationMinutes?.toString() ?? '',
      stops: (route?.stops ?? []).map((stop) => ({
        destinationId: stop.destinationId,
        serviceTimeOverrideMinutes: stop.serviceTimeOverrideMinutes?.toString() ?? '',
      })),
    },
  })
  const { fields, append, remove, move } = useFieldArray({ control, name: 'stops' })

  const addableDestinations = availableDestinations.filter(
    (destination) => !fields.some((field) => field.destinationId === destination.id),
  )

  function addStop() {
    if (stopToAdd === '') return
    // Added inheriting: a new stop takes as long as its location says until someone says
    // otherwise, which is right far more often than not.
    append({ destinationId: stopToAdd, serviceTimeOverrideMinutes: '' })
    setStopToAdd('')
  }

  async function onSubmit(values: RouteFormValues) {
    setFormError(null)
    if (values.stops.length === 0) {
      setFormError(t('routes.form.atLeastOneStop'))
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
      stops: values.stops.map(toStopRequest),
    }

    try {
      if (isEdit) {
        await updateRoute(companyId, route.id, request)
      } else {
        await createRoute(companyId, request)
      }
      onSaved()
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('routes.form.edit') : t('routes.form.create')}
      subtitle={t('routes.form.subtitle')}
      size="xl"
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
        <p className="alert alert-secondary small py-2">{t('routes.form.note')}</p>

        {formError && (
          <div className="alert alert-danger py-2 small" role="alert">
            {formError}
          </div>
        )}

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.identification')}</legend>
          <div className="row">
            <div className="col-12 col-sm-3">
              <FormField label={tc('columns.code')} htmlFor="route-code" error={errors.code?.message} required>
                <input
                  id="route-code"
                  className={`form-control${errors.code ? ' is-invalid' : ''}`}
                  {...register('code', {
                    required: tv('required'),
                    maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                    pattern: { value: CODE_PATTERN, message: tv('codePattern') },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-9">
              <FormField label={tc('columns.name')} htmlFor="route-name" error={errors.name?.message} required>
                <input
                  id="route-name"
                  className={`form-control${errors.name ? ' is-invalid' : ''}`}
                  {...register('name', {
                    required: tv('required'),
                    maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                  })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.operation')}</legend>
          <div className="row">
            <div className="col-12 col-sm-4">
              <FormField label={tc('columns.origin')} htmlFor="route-origin" error={errors.originId?.message} required>
                {/* Controller rather than `register`: the control is a button plus a listbox, so
                    there is no native change event for react-hook-form to hook into. */}
                <Controller
                  control={control}
                  name="originId"
                  rules={{ required: tv('required') }}
                  render={({ field }) => (
                    <Select
                      id="route-origin"
                      value={field.value}
                      onChange={field.onChange}
                      invalid={Boolean(errors.originId)}
                      options={[
                        { value: '', label: t('routes.form.selectOrigin') },
                        ...originOptions.map((origin) => ({ value: origin.id, label: origin.name })),
                      ]}
                    />
                  )}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField label={tc('columns.zone')} htmlFor="route-zone" error={errors.zoneId?.message}>
                <Controller
                  control={control}
                  name="zoneId"
                  render={({ field }) => (
                    <Select
                      id="route-zone"
                      value={field.value}
                      onChange={field.onChange}
                      options={[
                        { value: '', label: t('routes.form.noZone') },
                        ...zoneOptions.map((zone) => ({ value: zone.id, label: zone.name })),
                      ]}
                    />
                  )}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField label={tc('fields.frequency')} htmlFor="route-frequency" error={errors.frequencyId?.message}>
                <Controller
                  control={control}
                  name="frequencyId"
                  render={({ field }) => (
                    <Select
                      id="route-frequency"
                      value={field.value}
                      onChange={field.onChange}
                      options={[
                        { value: '', label: t('routes.form.noFrequency') },
                        ...frequencyOptions.map((frequency) => ({ value: frequency.id, label: frequency.name })),
                      ]}
                    />
                  )}
                />
              </FormField>
            </div>
            <div className="col-6">
              <FormField
                label={tc('fields.referenceDistanceKm')}
                htmlFor="route-distance"
                error={errors.referenceDistanceKm?.message}
              >
                <input
                  id="route-distance"
                  type="number"
                  min={0}
                  step="0.01"
                  className={`form-control${errors.referenceDistanceKm ? ' is-invalid' : ''}`}
                  {...register('referenceDistanceKm', { min: { value: 0, message: tv('nonNegative') } })}
                />
              </FormField>
            </div>
            <div className="col-6">
              <FormField
                label={tc('fields.referenceDurationMin')}
                htmlFor="route-duration"
                error={errors.referenceDurationMinutes?.message}
              >
                <input
                  id="route-duration"
                  type="number"
                  min={0}
                  className={`form-control${errors.referenceDurationMinutes ? ' is-invalid' : ''}`}
                  {...register('referenceDurationMinutes', { min: { value: 0, message: tv('nonNegative') } })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{t('routes.form.stopsLabel')}</legend>

          <p className="text-body-secondary small" id="route-stops-service-time-help">
            {t('routes.form.serviceTimeHelp')}
          </p>

          {fields.length === 0 && <p className="text-body-secondary small">{t('routes.form.noStops')}</p>}
          {fields.length > 0 && (
            <ol className="list-group list-group-numbered mb-2">
              {fields.map((field, index) => {
                const destination = destinationLookup.get(field.destinationId)
                const position = index + 1
                return (
                  <li
                    key={field.id}
                    className="list-group-item d-flex flex-wrap justify-content-between align-items-center gap-2"
                  >
                    <span className="tms-min-w-0 tms-truncate">
                      {destination ? `${destination.code} — ${destination.name}` : field.destinationId}
                    </span>
                    {/* Empty means "inherit", and the placeholder is the value that would apply -
                        so the box is never a blank the operator has to go and look up. */}
                    <span className="flex-shrink-0 d-flex align-items-center gap-1">
                      <label htmlFor={`route-stop-service-time-${field.id}`} className="visually-hidden">
                        {t('routes.form.serviceTimeOverride', { position })}
                      </label>
                      <input
                        id={`route-stop-service-time-${field.id}`}
                        type="number"
                        min={0}
                        className={`form-control form-control-sm tms-w-numeric-sm${
                          errors.stops?.[index]?.serviceTimeOverrideMinutes ? ' is-invalid' : ''
                        }`}
                        placeholder={destination?.serviceTimeMinutes?.toString() ?? '0'}
                        aria-describedby="route-stops-service-time-help"
                        {...register(`stops.${index}.serviceTimeOverrideMinutes` as const, {
                          min: { value: 0, message: tv('nonNegative') },
                        })}
                      />
                      <span className="text-body-secondary small">{tc('units.minutesShort')}</span>
                    </span>
                    <span className="btn-group btn-group-sm flex-shrink-0">
                      <button
                        type="button"
                        className="btn btn-outline-secondary"
                        aria-label={t('routes.form.moveUp', { position })}
                        disabled={index === 0}
                        onClick={() => move(index, index - 1)}
                      >
                        <i className="bi bi-arrow-up" aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn btn-outline-secondary"
                        aria-label={t('routes.form.moveDown', { position })}
                        disabled={index === fields.length - 1}
                        onClick={() => move(index, index + 1)}
                      >
                        <i className="bi bi-arrow-down" aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn btn-outline-danger"
                        aria-label={t('routes.form.removeStop', { position })}
                        onClick={() => remove(index)}
                      >
                        {t('routes.form.remove')}
                      </button>
                    </span>
                    {/* `w-100` inside the wrapping flex row: the message gets its own line
                        rather than squeezing the stop's name out of the way. */}
                    {errors.stops?.[index]?.serviceTimeOverrideMinutes && (
                      <div className="invalid-feedback d-block w-100 mb-0" role="alert">
                        {errors.stops?.[index]?.serviceTimeOverrideMinutes?.message}
                      </div>
                    )}
                  </li>
                )
              })}
            </ol>
          )}

          <div className="d-flex gap-2 align-items-start">
            {/* Not an `input-group`: `.tms-select` draws its own border and radius, unlike
                `.form-select`, so it does not need (or want) Bootstrap's input-group merging. */}
            <div className="flex-grow-1">
              <label htmlFor="route-stop-picker" className="visually-hidden">
                {tc('fields.destinationToAdd')}
              </label>
              <Select
                id="route-stop-picker"
                size="sm"
                value={stopToAdd}
                onChange={setStopToAdd}
                options={[
                  { value: '', label: t('routes.form.selectDestination') },
                  ...addableDestinations.map((destination) => ({
                    value: destination.id,
                    label: `${destination.code} — ${destination.name}`,
                  })),
                ]}
              />
            </div>
            <button
              type="button"
              className="btn btn-outline-primary btn-sm"
              onClick={addStop}
              disabled={stopToAdd === ''}
            >
              {t('routes.form.addStop')}
            </button>
          </div>
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
