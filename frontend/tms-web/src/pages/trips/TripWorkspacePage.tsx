import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import type { DriverLicenseStatus } from '../../shared/api/driversApi'
import type { ApiError } from '../../shared/api/httpClient'
import {
  arriveAtStop,
  cancelTrip,
  completeStop,
  completeTrip,
  dispatchTrip,
  downloadDeliveryEvidence,
  failStop,
  fetchTrip,
  fetchTripEvents,
  markTripReady,
  recordDelivery,
  reportTripException,
  resolveTripException,
  skipStop,
  startStopService,
  uploadDeliveryEvidence,
  type DeliveryEvidenceView,
  type OrderDeliveryView,
  type StopExecutionStatus,
  type TripAssignmentView,
  type TripDetailView,
  type TripExceptionView,
  type TripStatus,
  type TripStopView,
} from '../../shared/api/planningApi'
import { describeApiError, describePlanningError } from '../../shared/api/problemMessages'
import { fetchTripTracking } from '../../shared/api/trackingApi'
import { useCompany } from '../../shared/company/CompanyContext'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import {
  TripStopMap,
  type TripStopMapOrigin,
  type TripStopMapStop,
  type TripStopMapVehicle,
} from '../../shared/maps/TripStopMap'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import {
  CapacityBar,
  confirmDialog,
  ErrorState,
  PageHeader,
  promptDialog,
  StatusBadge,
} from '../../shared/ui/components'
import { LoadingState } from '../../shared/ui/components/LoadingState'
import { TripDriverDrawer } from '../planning/TripDriverDrawer'
import { DeliveryDrawer, type DeliveryValues } from './DeliveryDrawer'
import { DeliveryEvidenceDrawer, type DeliveryEvidenceValues } from './DeliveryEvidenceDrawer'
import { TripCostCard } from './TripCostCard'
import { TripProblemDrawer, type TripProblemValues } from './TripProblemDrawer'
import { TripTenderCard } from './TripTenderCard'
import { TripTimeline } from './TripTimeline'
import { TripTrackingCard } from './TripTrackingCard'
import {
  DELIVERY_RESULT_TONE,
  STOP_EXECUTION_TONE,
  TRIP_EXCEPTION_TONE,
  TRIP_STATUS_TONE,
} from '../../shared/ui/statusTones'

/** Same mapping the Drivers screen uses - see `DriversPage`'s comment for why UNRECORDED is
 * neutral rather than a warning. */
const LICENSE_TONE: Record<DriverLicenseStatus, 'success' | 'warning' | 'danger' | 'neutral'> = {
  VALID: 'success',
  EXPIRING_SOON: 'warning',
  EXPIRED: 'danger',
  UNRECORDED: 'neutral',
}

/** `LocalTime` strings from the backend ("HH:mm" or "HH:mm:ss") trimmed to "HH:mm" for display. */
function formatServiceWindow(start: string | null, end: string | null): string | null {
  if (!start && !end) return null
  const short = (value: string) => value.slice(0, 5)
  if (start && end) return `${short(start)}–${short(end)}`
  return short(start ?? end ?? '')
}

/**
 * The three stop transitions that need no reason: which endpoint each one calls and what it says
 * when it succeeds. A lookup rather than a chain of ternaries at the call site, so adding a fourth
 * is one entry and not three edits - and so the translation keys stay literals that `t()` can
 * key-check.
 */
const STOP_ACTIONS = {
  // `send` and not `call`: a property named `call` reads as Function.prototype.call at the call
  // site, which is exactly the kind of ambiguity a reviewer should not have to resolve.
  ARRIVED: { send: arriveAtStop, toastKey: 'workspace.toasts.stopArrived' },
  IN_SERVICE: { send: startStopService, toastKey: 'workspace.toasts.stopInService' },
  COMPLETED: { send: completeStop, toastKey: 'workspace.toasts.stopCompleted' },
} as const

/**
 * The three ways of saying "something went wrong" share one drawer, and differ only in what it is
 * titled and which endpoint it posts to. Keeping the copy here rather than deriving keys from the
 * mode keeps every key a literal.
 */
const PROBLEM_COPY = {
  skip: {
    title: 'workspace.problems.titles.skip',
    subtitle: 'workspace.problems.subtitles.skip',
    submit: 'workspace.problems.submit.skip',
    toastKey: 'workspace.toasts.stopSkipped',
  },
  fail: {
    title: 'workspace.problems.titles.fail',
    subtitle: 'workspace.problems.subtitles.fail',
    submit: 'workspace.problems.submit.fail',
    toastKey: 'workspace.toasts.stopFailed',
  },
  report: {
    title: 'workspace.problems.titles.report',
    subtitle: 'workspace.problems.subtitles.report',
    submit: 'workspace.problems.submit.report',
    toastKey: 'workspace.toasts.problemReported',
  },
} as const

/**
 * A `datetime-local` value ("2026-08-20T08:47") turned into the ISO instant the API takes.
 *
 * The browser's input has no time zone, so the value is read as local time - which is what the
 * operator meant, since they are standing in it - and `toISOString` converts it to UTC for the
 * wire. Returning `null` for an empty box is the whole point of the field being optional: the
 * server then stamps its own clock.
 */
function toInstant(localValue: string): string | null {
  if (localValue.trim() === '') return null
  const parsed = new Date(localValue)
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString()
}

/**
 * The trip workspace (`docs/domain/TRIP_EXECUTION_V1.md`): one shipment, its lifecycle, what it
 * carries, where it goes, and the actions that move it through the day.
 *
 * <p>A full page and not a modal, deliberately. A dispatcher works inside one trip for minutes at
 * a time - reading stops off it, comparing planned against actual - and a dialog that dims the
 * rest of the app is the wrong container for that. It is also linkable: `/trips/{id}` is what
 * gets pasted into a chat when someone asks where a truck is.
 *
 * <p>Which buttons exist is `trip.allowedTransitions`, decided server-side. This screen never
 * derives the lifecycle from `status` itself - see `planningApi.TripView`.
 */
export function TripWorkspacePage() {
  const { t } = useTranslation('trips')
  const { t: tRates } = useTranslation('rates')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const { tripId } = useParams<{ tripId: string }>()
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canExecute = hasPermission('planning.trip:execute')
  const canCancel = canExecute || hasPermission('planning.trip:manage')
  /**
   * Tracking is a separate authority, so the card is simply absent for a role without it - not
   * greyed out, and not asked for and hidden. The endpoint enforces the same rule; this only
   * avoids a 403 on every visit for roles that will never be allowed one.
   */
  const canMonitor = hasPermission('monitoring.transport:read')
  /**
   * What a shipment cost is its own authority too, and for a sharper reason than tracking's: it
   * is commercial information, and an installation may well want a dispatcher to run the day
   * without seeing what the day is worth. Absent, not greyed out, for a role without it.
   */
  const canReadCost = hasPermission('rates.trip_cost:read')
  const canManageCost = hasPermission('rates.trip_cost:manage')
  /**
   * Tendering is a third authority again, and commercially sensitive for the same reason costing
   * is: an offer carries a price. Absent, not greyed out, for a role without it - and separate from
   * `planning.trip:manage`, because placing a load with a carrier and building the plan are
   * different jobs (migration V31).
   */
  const canReadTenders = hasPermission('planning.tender:read')
  const canManageTenders = hasPermission('planning.tender:manage')
  const queryClient = useQueryClient()

  const queryKey = ['trip', companyId, tripId]
  const tripQuery = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchTrip(companyId, tripId as string, signal),
    enabled: companyId !== '' && tripId !== undefined,
  })

  /**
   * The timeline is its own query, not part of the trip: it grows all day, it is read rather than
   * acted on, and keeping it separate is what stops every stop action re-sending forty entries.
   * Both are invalidated together by `refresh` - see the backend's `TripDetailView` comment.
   */
  const eventsQueryKey = ['trip-events', companyId, tripId]
  const eventsQuery = useQuery({
    queryKey: eventsQueryKey,
    queryFn: ({ signal }) => fetchTripEvents(companyId, tripId as string, signal),
    enabled: companyId !== '' && tripId !== undefined,
  })

  /**
   * Where the vehicle is (`docs/domain/TRACKING_V1.md`). A third query rather than a field on the
   * trip, for a stronger version of the timeline's reason: it is behind another permission
   * (`monitoring.transport:read`), it can consult an external provider, and it is the one read on
   * this page that may legitimately fail while everything else works. `retry: false` keeps a
   * deployment with no feed from spending three round trips per visit discovering that again.
   */
  const trackingQuery = useQuery({
    queryKey: ['trip-tracking', companyId, tripId],
    queryFn: ({ signal }) => fetchTripTracking(companyId, tripId as string, signal),
    enabled: companyId !== '' && tripId !== undefined && canMonitor,
    retry: false,
  })

  /** The operator-supplied actual time, empty by default - see `time.hint`. */
  const [occurredAt, setOccurredAt] = useState('')
  const [selectedStopId, setSelectedStopId] = useState<string | null>(null)
  const [showDriverModal, setShowDriverModal] = useState(false)
  /**
   * Which "something went wrong" drawer is open, if any. One state and not three booleans: the
   * three flows share a form, and a shape that cannot represent two of them at once is a shape
   * that cannot open two of them at once.
   */
  const [problem, setProblem] = useState<{ mode: 'skip' | 'fail' | 'report'; stopId?: string } | null>(
    null,
  )
  /**
   * The delivery being recorded or corrected, if any. Carries the labels the drawer prints so it
   * needs no second lookup, and the existing record when this is a correction - which is what makes
   * the same drawer serve both.
   */
  const [delivery, setDelivery] = useState<{
    stopId: string
    stopLabel: string
    orderId: string
    orderNumber: string
    existing?: OrderDeliveryView
  } | null>(null)
  /** The recorded delivery an artefact is being attached to. Only reachable once one exists. */
  const [evidenceFor, setEvidenceFor] = useState<{ deliveryId: string; orderNumber: string } | null>(null)
  const [busy, setBusy] = useState(false)

  const detail: TripDetailView | undefined = tripQuery.data

  const mapOrigin = useMemo<TripStopMapOrigin | null>(() => {
    if (!detail || !detail.trip.originId) return null
    return {
      latitude: detail.trip.originLatitude,
      longitude: detail.trip.originLongitude,
      label: detail.trip.originName ?? detail.trip.originCode ?? t('workspace.fields.origin'),
    }
  }, [detail, t])

  const mapStops = useMemo<TripStopMapStop[]>(
    () =>
      (detail?.stops ?? []).map((stop) => ({
        id: stop.destinationId,
        sequence: stop.sequence,
        latitude: stop.latitude,
        longitude: stop.longitude,
        label: stop.destinationName ?? stop.destinationCode ?? '',
      })),
    [detail],
  )

  /**
   * The vehicle's marker, drawn only while the shipment is actually out. A stored position from
   * this morning on a trip that is already back is a true fact and a misleading pin, so the map
   * shows it while it means "here it is" and the card keeps reporting it with its age either way.
   */
  const mapVehicle = useMemo<TripStopMapVehicle | null>(() => {
    const tracking = trackingQuery.data
    if (!tracking?.trackable || !tracking.lastPosition) return null
    return {
      latitude: tracking.lastPosition.latitude,
      longitude: tracking.lastPosition.longitude,
      label: tracking.vehicleLicensePlate ?? tracking.vehicleCode ?? tracking.shipmentNumber,
    }
  }, [trackingQuery.data])

  function refresh() {
    void queryClient.invalidateQueries({ queryKey })
    // Every write in this module appends to the timeline, so it is never refreshed on its own.
    void queryClient.invalidateQueries({ queryKey: eventsQueryKey })
    // Tracking is refreshed too, not because a write produces positions - nothing in TMS does -
    // but because dispatching and completing change whether the shipment is on the road, and the
    // card says something different in each case.
    void queryClient.invalidateQueries({ queryKey: ['trip-tracking', companyId, tripId] })
    // The list behind this page shows the same status and the same capacity; leaving it stale
    // means a dispatcher who navigates back sees a trip they just dispatched as still ready.
    void queryClient.invalidateQueries({ queryKey: ['trips', companyId] })
  }

  /**
   * Every transition follows the same five steps, so they share one function: confirm, send the
   * version the screen was rendered from, toast, refresh, and translate a refusal into the
   * planning vocabulary. `busy` is what stops a double click producing a second request - the
   * backend would answer the retry idempotently, but a spinner is a better answer than a race.
   */
  async function run(
    action: (version: number, at: string | null) => Promise<unknown>,
    successMessage: string,
  ) {
    if (!detail || busy) return
    setBusy(true)
    try {
      await action(detail.trip.version, toInstant(occurredAt))
      setOccurredAt('')
      notifySuccess(successMessage, detail.trip.shipmentNumber)
      refresh()
    } catch (error) {
      notifyError(t('workspace.toasts.error'), describePlanningError(error as ApiError))
    } finally {
      setBusy(false)
    }
  }

  async function markReady() {
    if (!detail) return
    const confirmed = await confirmDialog({
      title: t('workspace.dialogs.readyTitle'),
      text: t('workspace.dialogs.readyText', { number: detail.trip.shipmentNumber }),
      confirmLabel: t('workspace.actions.ready'),
    })
    if (!confirmed) return
    await run((version, at) => markTripReady(companyId, detail.trip.id, { version, occurredAt: at }),
      t('workspace.toasts.ready'))
  }

  async function dispatch() {
    if (!detail) return
    const confirmed = await confirmDialog({
      title: t('workspace.dialogs.dispatchTitle'),
      text: t('workspace.dialogs.dispatchText', { number: detail.trip.shipmentNumber }),
      confirmLabel: t('workspace.actions.dispatch'),
    })
    if (!confirmed) return
    await run((version, at) => dispatchTrip(companyId, detail.trip.id, { version, occurredAt: at }),
      t('workspace.toasts.dispatched'))
  }

  async function complete() {
    if (!detail) return
    const confirmed = await confirmDialog({
      title: t('workspace.dialogs.completeTitle'),
      text: t('workspace.dialogs.completeText', { number: detail.trip.shipmentNumber }),
      confirmLabel: t('workspace.actions.complete'),
      // Terminal and irreversible: the same treatment every destructive action in the app gets.
      dangerous: true,
    })
    if (!confirmed) return
    await run((version, at) => completeTrip(companyId, detail.trip.id, { version, occurredAt: at }),
      t('workspace.toasts.completed'))
  }

  /**
   * Cancellation asks for the reason inside the confirmation rather than after it. The backend
   * requires one for a trip that was ever confirmed - which every trip reachable from this screen
   * is - so a dialog that confirmed first and asked second would be a round trip to a 400.
   */
  async function cancel() {
    if (!detail || busy) return
    const reason = await promptDialog({
      title: t('workspace.dialogs.cancelTitle'),
      text: t('workspace.dialogs.cancelText', { number: detail.trip.shipmentNumber }),
      inputLabel: t('workspace.dialogs.cancelReasonLabel'),
      inputPlaceholder: t('workspace.dialogs.cancelReasonPlaceholder'),
      required: true,
      requiredMessage: t('workspace.dialogs.cancelReasonRequired'),
      maxLength: 500,
      confirmLabel: t('workspace.actions.cancel'),
      dangerous: true,
    })
    if (reason === null) return

    setBusy(true)
    try {
      await cancelTrip(companyId, detail.trip.id, { version: detail.trip.version, reason })
      notifySuccess(t('workspace.toasts.cancelled'), detail.trip.shipmentNumber)
      refresh()
    } catch (error) {
      notifyError(t('workspace.toasts.error'), describePlanningError(error as ApiError))
    } finally {
      setBusy(false)
    }
  }

  /**
   * The three stop actions that need no reason. They share `run`'s shape but not its signature:
   * a stop action sends no `version` (the backend serialises them on the trip's row lock, see
   * `TripStopExecutionRequest`) and carries the same operator-supplied time the trip actions do,
   * so recording an arrival at 11:04 from a desk at 11:40 works exactly as it does for a
   * departure.
   */
  async function runStopAction(stop: TripStopView, outcome: 'ARRIVED' | 'IN_SERVICE' | 'COMPLETED') {
    if (!detail || busy) return
    const action = STOP_ACTIONS[outcome]
    setBusy(true)
    try {
      await action.send(companyId, detail.trip.id, stop.id, {
        occurredAt: toInstant(occurredAt),
        notes: null,
      })
      setOccurredAt('')
      notifySuccess(t(action.toastKey), stop.destinationName ?? stop.destinationCode ?? '')
      refresh()
    } catch (error) {
      notifyError(t('workspace.toasts.error'), describePlanningError(error as ApiError))
    } finally {
      setBusy(false)
    }
  }

  /**
   * Skipping a stop, failing one, and reporting a trip-level problem all end here: the drawer
   * collects a typed reason and a sentence, and which of the three endpoints they go to is what
   * `problem.mode` remembers. The drawer stays open on a refusal, with the message in it, so a
   * dispatcher who chose a reason the server rejected does not lose what they typed.
   */
  async function submitProblem(values: TripProblemValues) {
    if (!detail || problem === null) return
    const { mode, stopId } = problem
    try {
      if (mode === 'report') {
        await reportTripException(companyId, detail.trip.id, {
          tripStopId: values.tripStopId,
          exceptionType: values.exceptionType,
          occurredAt: toInstant(occurredAt),
          notes: values.notes,
        })
      } else {
        const call = mode === 'skip' ? skipStop : failStop
        await call(companyId, detail.trip.id, stopId as string, {
          occurredAt: toInstant(occurredAt),
          exceptionType: values.exceptionType,
          notes: values.notes,
        })
      }
    } catch (error) {
      // Rethrown as a plain Error so the drawer renders the server's sentence in its own alert
      // rather than as a toast behind a modal nobody can read past.
      throw new Error(describePlanningError(error as ApiError))
    }
    setProblem(null)
    setOccurredAt('')
    notifySuccess(t(PROBLEM_COPY[mode].toastKey), detail.trip.shipmentNumber)
    refresh()
  }

  /**
   * Recording - or correcting - what was handed over for one order at one stop.
   *
   * <p>A refusal is rethrown as a plain `Error` so the drawer renders the server's sentence in its
   * own alert rather than as a toast behind a modal nobody can read past: the same contract
   * `submitProblem` follows, and it matters more here, because the rules a delivery can break
   * ("DELIVERED must say when", "a receiver cannot be named on a failed attempt") are exactly the
   * ones an operator needs to see next to the field they typed.
   */
  async function submitDelivery(values: DeliveryValues) {
    if (!detail || delivery === null) return
    try {
      await recordDelivery(companyId, detail.trip.id, delivery.stopId, delivery.orderId, {
        result: values.result,
        deliveredAt: values.deliveredAt === null ? null : toInstant(values.deliveredAt),
        receiverName: values.receiverName,
        receiverDocument: values.receiverDocument,
        notes: values.notes,
      })
    } catch (error) {
      throw new Error(describePlanningError(error as ApiError))
    }
    setDelivery(null)
    notifySuccess(t('workspace.toasts.deliveryRecorded'), delivery.orderNumber)
    refresh()
  }

  /**
   * Attaching an artefact. A deployment with no evidence store answers 503 and the drawer shows the
   * server's own sentence, which says that delivery results are recorded without it - a
   * configuration fact, not an error the operator caused.
   */
  async function submitEvidence(values: DeliveryEvidenceValues) {
    if (!detail || evidenceFor === null) return
    try {
      await uploadDeliveryEvidence(companyId, detail.trip.id, evidenceFor.deliveryId, {
        evidenceType: values.evidenceType,
        capturedAt: values.capturedAt === null ? null : toInstant(values.capturedAt),
        file: values.file,
      })
    } catch (error) {
      throw new Error(describePlanningError(error as ApiError))
    }
    setEvidenceFor(null)
    notifySuccess(t('workspace.toasts.evidenceAttached'), evidenceFor.orderNumber)
    refresh()
  }

  /**
   * Fetches one artefact and hands it to the browser to save.
   *
   * <p>Through the API and not through a link, because there is no address these bytes are
   * reachable at: the request carries the bearer token and the company header like every other, and
   * the blob URL is revoked as soon as the synthetic click has taken its own reference - the same
   * shape `ImportDrawer` uses for a template download.
   */
  async function downloadEvidence(deliveryId: string, evidence: DeliveryEvidenceView) {
    if (!detail) return
    try {
      const downloaded = await downloadDeliveryEvidence(companyId, detail.trip.id, deliveryId, evidence.id)
      const url = URL.createObjectURL(downloaded.blob)
      const link = document.createElement('a')
      link.href = url
      link.download = downloaded.fileName ?? evidence.originalFilename ?? `evidence-${evidence.id}`
      link.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      notifyError(t('workspace.toasts.error'), describePlanningError(error as ApiError))
    }
  }

  /**
   * Closing a problem out. The note is required by the API and asked for inside the confirmation
   * for the same reason cancellation's reason is: confirming first and asking second would be a
   * round trip to a 400.
   */
  async function resolveProblem(exception: TripExceptionView) {
    if (!detail || busy) return
    const notes = await promptDialog({
      title: t('workspace.dialogs.resolveTitle'),
      text: t('workspace.dialogs.resolveText', {
        type: enumLabels.tripExceptionType(exception.exceptionType),
      }),
      inputLabel: t('workspace.dialogs.resolveNotesLabel'),
      inputPlaceholder: t('workspace.dialogs.resolveNotesPlaceholder'),
      required: true,
      requiredMessage: t('workspace.dialogs.resolveNotesRequired'),
      maxLength: 1000,
      confirmLabel: t('workspace.problems.resolve'),
    })
    if (notes === null) return

    setBusy(true)
    try {
      await resolveTripException(companyId, detail.trip.id, exception.id, { notes })
      notifySuccess(t('workspace.toasts.problemResolved'), detail.trip.shipmentNumber)
      refresh()
    } catch (error) {
      notifyError(t('workspace.toasts.error'), describePlanningError(error as ApiError))
    } finally {
      setBusy(false)
    }
  }

  if (tripQuery.isPending) {
    return <LoadingState label={t('workspace.loading')} />
  }
  if (tripQuery.isError || !detail) {
    return (
      <ErrorState
        message={describeApiError(tripQuery.error as ApiError)}
        onRetry={() => void tripQuery.refetch()}
      />
    )
  }

  const { trip, assignments, stops, exceptions, deliveries } = detail
  const allowed = (status: TripStatus) => trip.allowedTransitions.includes(status)
  /** Whether the server still offers this outcome for this stop. Never derived from its status. */
  const stopAllows = (stop: TripStopView, outcome: StopExecutionStatus) =>
    stop.allowedExecutionTransitions.includes(outcome)
  const openProblems = exceptions.filter((exception) => exception.status === 'OPEN').length

  /**
   * The delivery recorded for an order, or undefined when nobody has recorded one. The absence is
   * the state - there is no `PENDING` result - so the UI says "not recorded" rather than inventing
   * an outcome the API would never send.
   */
  const deliveryByOrder = new Map<string, OrderDeliveryView>(
    deliveries.map((recorded) => [recorded.orderId, recorded]),
  )

  /** The orders a stop serves: the ones whose destination is the one the stop goes to. */
  const ordersAtStop = (stop: TripStopView): TripAssignmentView[] =>
    assignments.filter((assignment) => assignment.destinationId === stop.destinationId)

  /**
   * Whether this shipment has a delivery story to tell at all. Before departure it has none - and
   * the stops list stays what it was, a plan - so the per-order block is not drawn: a column of
   * "Not recorded" against a trip that has not left is noise that says nothing.
   *
   * <p>`COMPLETED` counts, and that is the point: the paperwork comes home at the end of the day,
   * and the server allows a closed shipment for exactly that reason.
   */
  const tripHasRun = trip.status === 'IN_TRANSIT' || trip.status === 'COMPLETED'

  /** Mirrored only to decide whether to draw a button; the server re-checks and refuses its own way. */
  const canRecordDeliveries = canExecute && tripHasRun

  const stopLabelOf = (stop: TripStopView) =>
    `${stop.sequence}. ${stop.destinationName ?? stop.destinationCode ?? ''}`

  /**
   * Naming the driver stays available right up to departure - a driver calling in sick at 05:00 on
   * a shipment confirmed last night is the ordinary case, not a re-plan. Derived from the same
   * `allowedTransitions` the buttons above use rather than from a second list of states here: a
   * trip that can still be made ready or dispatched has not left, and that is exactly the window
   * the server allows. It re-checks anyway.
   */
  const canAssignDriver =
    (canExecute || hasPermission('planning.trip:manage')) &&
    (allowed('READY_FOR_DISPATCH') || allowed('IN_TRANSIT'))

  /**
   * The departure gap, in whole minutes, or null when either half is missing. Computed here and
   * not on the server because it is presentation: the two instants are the facts, and the number
   * of minutes between them is one way of reading them.
   */
  const departureDelayMinutes =
    trip.plannedDepartureAt !== null && trip.actualDepartureAt !== null
      ? Math.round(
          (new Date(trip.actualDepartureAt).getTime() - new Date(trip.plannedDepartureAt).getTime()) / 60000,
        )
      : null

  function delayLabel(minutes: number): string {
    if (minutes === 0) return t('workspace.lifecycle.onTime')
    return minutes > 0
      ? t('workspace.lifecycle.delayLate', { minutes })
      : t('workspace.lifecycle.delayEarly', { minutes: -minutes })
  }

  const timeline: { key: string; label: string; value: string | null }[] = [
    { key: 'planned', label: t('workspace.lifecycle.planned'), value: trip.plannedDepartureAt },
    { key: 'ready', label: t('workspace.lifecycle.ready'), value: trip.readyAt },
    { key: 'departed', label: t('workspace.lifecycle.departed'), value: trip.actualDepartureAt },
    { key: 'completed', label: t('workspace.lifecycle.completed'), value: trip.actualCompletionAt },
  ]

  return (
    <div>
      <Link to="/trips" className="text-decoration-none small d-inline-flex align-items-center gap-1 mb-2">
        <i className="bi bi-arrow-left" aria-hidden="true" />
        {t('workspace.back')}
      </Link>

      <PageHeader
        icon="truck"
        title={trip.shipmentNumber}
        meta={
          <>
            <StatusBadge label={enumLabels.tripStatus(trip.status)} tone={TRIP_STATUS_TONE[trip.status]} />
            <span className="tms-badge tms-badge-neutral">
              {t('workspace.ordersCount', { count: trip.orderCount })}
            </span>
            <span className="tms-badge tms-badge-neutral">
              {t('workspace.stopsCount', { count: trip.stopCount })}
            </span>
          </>
        }
        description={`${trip.originName ?? trip.originCode ?? ''} · ${format.date(trip.planningDate)}${
          trip.planNumber === null ? '' : ` · ${t('workspace.plan')} ${trip.planNumber}`
        }`}
        actions={
          <div className="d-flex flex-wrap align-items-end gap-2">
            {canExecute && (allowed('READY_FOR_DISPATCH') || allowed('IN_TRANSIT') || allowed('COMPLETED')) && (
              <div>
                <label htmlFor="trip-occurred-at" className="form-label small mb-1">
                  {t('workspace.time.label')}
                </label>
                <input
                  id="trip-occurred-at"
                  type="datetime-local"
                  className="form-control form-control-sm"
                  aria-describedby="trip-occurred-at-hint"
                  value={occurredAt}
                  onChange={(event) => setOccurredAt(event.target.value)}
                />
                <div id="trip-occurred-at-hint" className="form-text small">
                  {t('workspace.time.hint')}
                </div>
              </div>
            )}
            {canCancel && allowed('CANCELLED') && (
              <button type="button" className="btn btn-sm btn-outline-danger" disabled={busy} onClick={() => void cancel()}>
                {t('workspace.actions.cancel')}
              </button>
            )}
            {canExecute && allowed('READY_FOR_DISPATCH') && (
              <button type="button" className="btn btn-sm btn-primary" disabled={busy} onClick={() => void markReady()}>
                {t('workspace.actions.ready')}
              </button>
            )}
            {canExecute && allowed('IN_TRANSIT') && (
              <button type="button" className="btn btn-sm btn-primary" disabled={busy} onClick={() => void dispatch()}>
                {t('workspace.actions.dispatch')}
              </button>
            )}
            {canExecute && allowed('COMPLETED') && (
              <button type="button" className="btn btn-sm btn-primary" disabled={busy} onClick={() => void complete()}>
                {t('workspace.actions.complete')}
              </button>
            )}
          </div>
        }
      />

      <div className="row g-3">
        <div className="col-12 col-lg-5">
          <section className="tms-card p-3 mb-3">
            <h2 className="tms-section-title mb-2">{t('workspace.sections.lifecycle')}</h2>
            <dl className="row mb-0 small">
              {timeline.map((entry) => (
                <div className="col-12 d-flex justify-content-between border-bottom py-1" key={entry.key}>
                  <dt className="fw-normal text-secondary">{entry.label}</dt>
                  <dd className="mb-0 text-end">
                    {entry.value === null ? (
                      <span className="text-secondary">{t('workspace.lifecycle.pending')}</span>
                    ) : (
                      format.dateTime(entry.value)
                    )}
                    {entry.key === 'departed' && departureDelayMinutes !== null && (
                      <span className="d-block text-secondary">{delayLabel(departureDelayMinutes)}</span>
                    )}
                  </dd>
                </div>
              ))}
              {trip.cancelledAt !== null && (
                <div className="col-12 d-flex justify-content-between py-1">
                  <dt className="fw-normal text-secondary">{t('workspace.lifecycle.cancelled')}</dt>
                  <dd className="mb-0 text-end">
                    {format.dateTime(trip.cancelledAt)}
                    {trip.cancelReason !== null && (
                      <span className="d-block text-secondary">
                        {t('workspace.lifecycle.reason')}: {trip.cancelReason}
                      </span>
                    )}
                  </dd>
                </div>
              )}
            </dl>
          </section>

          <section className="tms-card p-3 mb-3">
            <h2 className="tms-section-title mb-2">{t('workspace.sections.assignment')}</h2>
            <dl className="row mb-0 small">
              {[
                { label: t('workspace.fields.carrier'), value: trip.carrierName },
                { label: t('workspace.fields.vehicle'), value: trip.vehicleCode },
                { label: t('workspace.fields.plate'), value: trip.vehicleLicensePlate },
                { label: t('workspace.fields.vehicleType'), value: trip.vehicleTypeCode },
                { label: t('workspace.fields.driver'), value: trip.driverName },
                { label: t('workspace.fields.driverPhone'), value: trip.driverPhone },
                { label: t('workspace.fields.route'), value: trip.routeName ?? trip.routeCode },
              ].map((field) => (
                <div className="col-12 d-flex justify-content-between py-1" key={field.label}>
                  <dt className="fw-normal text-secondary">{field.label}</dt>
                  <dd className="mb-0 text-end">
                    {field.value ?? <span className="text-secondary">{t('workspace.fields.none')}</span>}
                  </dd>
                </div>
              ))}
              {/* The licence gets its own row rather than a suffix on the driver's name: it is the
                  one fact on this card that can stop the trip leaving, and the badge is how a
                  dispatcher sees that before they press Dispatch and read a refusal instead. */}
              {trip.driverLicenseNumber !== null && (
                <div className="col-12 d-flex justify-content-between py-1">
                  <dt className="fw-normal text-secondary">{t('workspace.fields.driverLicense')}</dt>
                  <dd className="mb-0 text-end">
                    <span className="tms-code">{trip.driverLicenseNumber}</span>
                    <span className="d-block">
                      <StatusBadge
                        label={enumLabels.driverLicenseStatus(trip.driverLicenseStatus ?? 'UNRECORDED')}
                        tone={LICENSE_TONE[trip.driverLicenseStatus ?? 'UNRECORDED']}
                      />
                      {trip.driverLicenseExpiresOn !== null && (
                        <span className="text-secondary"> {format.date(trip.driverLicenseExpiresOn)}</span>
                      )}
                    </span>
                  </dd>
                </div>
              )}
            </dl>
            {canAssignDriver && (
              <button
                type="button"
                className="btn btn-sm btn-outline-secondary mt-2"
                disabled={busy}
                onClick={() => setShowDriverModal(true)}
              >
                {trip.driverId ? t('workspace.actions.changeDriver') : t('workspace.actions.assignDriver')}
              </button>
            )}
            <div className="mt-3">
              <CapacityBar kind="weight" dimension={trip.capacity.weight} />
              <CapacityBar kind="volume" dimension={trip.capacity.volume} />
              <CapacityBar kind="pallets" dimension={trip.capacity.pallets} />
            </div>
          </section>

          {/* Directly under the shipment header, and above the cost: "has the carrier agreed to
              run this" is a question about whether the day happens at all, while the cost is a
              question about what it was worth. Absent entirely without planning.tender:read. */}
          {canReadTenders && (
            <section className="tms-card p-3 mb-3">
              <h2 className="tms-section-title mb-2">{t('tender.title')}</h2>
              <TripTenderCard
                companyId={companyId}
                tripId={trip.id}
                carrierName={trip.carrierName}
                offerable={trip.status === 'CONFIRMED' || trip.status === 'READY_FOR_DISPATCH'}
                canManage={canManageTenders}
              />
            </section>
          )}

          {/* Below the load and not beside it: the cost is what the load came to, and a
              dispatcher reads it after the shipment, not instead of it. */}
          {canReadCost && (
            <section className="tms-card p-3 mb-3">
              <h2 className="tms-section-title mb-2">{tRates('tripCost.title')}</h2>
              <TripCostCard companyId={companyId} tripId={trip.id} canManage={canManageCost} />
            </section>
          )}
        </div>

        <div className="col-12 col-lg-7 tms-min-w-0">
          {/* Above the stops, and therefore above the map that carries its pin: "where is it" is
              the question a dispatcher opens this page with, and the answer should not be below
              the fold on a laptop. Absent entirely without monitoring.transport:read. */}
          {canMonitor && (
            <section className="tms-card p-3 mb-3">
              <h2 className="tms-section-title mb-2">{t('workspace.sections.tracking')}</h2>
              <TripTrackingCard
                tracking={trackingQuery.data}
                loading={trackingQuery.isPending}
                failed={trackingQuery.isError}
              />
            </section>
          )}

          <section className="tms-card p-3 mb-3">
            <h2 className="tms-section-title mb-2">{t('workspace.sections.stops')}</h2>
            {stops.length === 0 ? (
              <p className="text-secondary small mb-0">{t('workspace.noStops')}</p>
            ) : (
              <>
                <TripStopMap
                  origin={mapOrigin}
                  stops={mapStops}
                  vehicle={mapVehicle}
                  selectedStopId={selectedStopId}
                  onSelectStop={setSelectedStopId}
                  height={260}
                />
                <ol className="list-group list-group-numbered mt-3 small">
                  {stops.map((stop) => (
                    <li
                      key={stop.id}
                      className={`list-group-item ${selectedStopId === stop.destinationId ? 'active' : ''}`}
                      onClick={() => setSelectedStopId(stop.destinationId)}
                    >
                      <div className="d-flex justify-content-between align-items-start gap-2">
                        <span className="tms-min-w-0">
                          <span className="fw-semibold">{stop.destinationName ?? stop.destinationCode}</span>
                          <span className="ms-2">
                            <StatusBadge
                              label={enumLabels.stopExecutionStatus(stop.executionStatus)}
                              tone={STOP_EXECUTION_TONE[stop.executionStatus]}
                            />
                          </span>
                          {stop.openExceptionCount > 0 && (
                            <span className="ms-1 tms-badge tms-badge-danger">
                              {t('workspace.stops.openProblems', { count: stop.openExceptionCount })}
                            </span>
                          )}
                          {stop.address !== null && <span className="d-block text-secondary">{stop.address}</span>}
                        </span>
                        <span className="text-end text-nowrap">
                          {t('workspace.ordersCount', { count: stop.orderCount })}
                          {formatServiceWindow(stop.serviceWindowStart, stop.serviceWindowEnd) !== null && (
                            <span className="d-block text-secondary">
                              {formatServiceWindow(stop.serviceWindowStart, stop.serviceWindowEnd)}
                            </span>
                          )}
                        </span>
                      </div>

                      {/* The actual times, only once there are any: a PENDING stop showing three
                          empty labels is noise on a board of twelve. */}
                      {stop.actualArrivalAt !== null && (
                        <div className="text-secondary mt-1">
                          {t('workspace.stops.arrived')}: {format.dateTime(stop.actualArrivalAt)}
                          {stop.serviceStartedAt !== null && (
                            <> · {t('workspace.stops.serviceStarted')}: {format.dateTime(stop.serviceStartedAt)}</>
                          )}
                          {stop.actualDepartureAt !== null && (
                            <> · {t('workspace.stops.departed')}: {format.dateTime(stop.actualDepartureAt)}</>
                          )}
                          {stop.dwellMinutes !== null && (
                            <> · {t('workspace.stops.dwell', { minutes: stop.dwellMinutes })}</>
                          )}
                        </div>
                      )}
                      {stop.executionNotes !== null && (
                        <div className="mt-1 fst-italic">{stop.executionNotes}</div>
                      )}

                      {canExecute && stop.allowedExecutionTransitions.length > 0 && (
                        <div className="d-flex flex-wrap gap-1 mt-2">
                          {stopAllows(stop, 'ARRIVED') && (
                            <button
                              type="button"
                              className="btn btn-sm btn-outline-primary"
                              disabled={busy}
                              onClick={() => void runStopAction(stop, 'ARRIVED')}
                            >
                              {t('workspace.stops.actions.arrive')}
                            </button>
                          )}
                          {stopAllows(stop, 'IN_SERVICE') && (
                            <button
                              type="button"
                              className="btn btn-sm btn-outline-secondary"
                              disabled={busy}
                              onClick={() => void runStopAction(stop, 'IN_SERVICE')}
                            >
                              {t('workspace.stops.actions.startService')}
                            </button>
                          )}
                          {stopAllows(stop, 'COMPLETED') && (
                            <button
                              type="button"
                              className="btn btn-sm btn-primary"
                              disabled={busy}
                              onClick={() => void runStopAction(stop, 'COMPLETED')}
                            >
                              {t('workspace.stops.actions.complete')}
                            </button>
                          )}
                          {stopAllows(stop, 'SKIPPED') && (
                            <button
                              type="button"
                              className="btn btn-sm btn-outline-warning"
                              disabled={busy}
                              onClick={() => setProblem({ mode: 'skip', stopId: stop.id })}
                            >
                              {t('workspace.stops.actions.skip')}
                            </button>
                          )}
                          {stopAllows(stop, 'FAILED') && (
                            <button
                              type="button"
                              className="btn btn-sm btn-outline-danger"
                              disabled={busy}
                              onClick={() => setProblem({ mode: 'fail', stopId: stop.id })}
                            >
                              {t('workspace.stops.actions.fail')}
                            </button>
                          )}
                        </div>
                      )}

                      {/* What was handed over here, order by order. Under the stop rather than in a
                          section of its own: "what happened at this customer" is one question, and
                          the stop's own outcome and its orders' outcomes are two halves of it. */}
                      {tripHasRun && ordersAtStop(stop).length > 0 && (
                        <ul className="list-unstyled border-top mt-2 pt-2 mb-0">
                          {ordersAtStop(stop).map((assignment) => {
                            const recorded = deliveryByOrder.get(assignment.orderId)
                            return (
                              <li
                                key={assignment.assignmentId}
                                className="d-flex justify-content-between align-items-start gap-2 py-1"
                              >
                                <span className="tms-min-w-0">
                                  <span className="fw-semibold">{assignment.orderNumber}</span>
                                  <span className="ms-2">
                                    {recorded === undefined ? (
                                      <span className="tms-badge tms-badge-neutral">
                                        {t('workspace.deliveries.notRecorded')}
                                      </span>
                                    ) : (
                                      <StatusBadge
                                        label={enumLabels.deliveryResult(recorded.result)}
                                        tone={DELIVERY_RESULT_TONE[recorded.result]}
                                      />
                                    )}
                                  </span>
                                  {recorded?.deliveredAt != null && (
                                    <span className="d-block text-secondary">
                                      {format.dateTime(recorded.deliveredAt)}
                                      {recorded.receiverName !== null && (
                                        <>
                                          {' · '}
                                          {t('workspace.deliveries.receivedBy', { name: recorded.receiverName })}
                                        </>
                                      )}
                                    </span>
                                  )}
                                  {recorded?.notes != null && (
                                    <span className="d-block fst-italic">{recorded.notes}</span>
                                  )}
                                  {recorded !== undefined && recorded.evidence.length > 0 && (
                                    <span className="d-flex flex-wrap gap-1 mt-1">
                                      {recorded.evidence.map((evidence) => (
                                        <button
                                          key={evidence.id}
                                          type="button"
                                          className="btn btn-sm btn-outline-secondary py-0"
                                          onClick={() => void downloadEvidence(recorded.id, evidence)}
                                        >
                                          <i className="bi bi-paperclip" aria-hidden="true" />{' '}
                                          {enumLabels.evidenceType(evidence.evidenceType)}
                                        </button>
                                      ))}
                                    </span>
                                  )}
                                </span>
                                {canRecordDeliveries && stop.executionStatus !== 'PENDING' && (
                                  <span className="d-flex gap-1 text-nowrap">
                                    <button
                                      type="button"
                                      className="btn btn-sm btn-outline-primary"
                                      disabled={busy}
                                      onClick={() =>
                                        setDelivery({
                                          stopId: stop.id,
                                          stopLabel: stopLabelOf(stop),
                                          orderId: assignment.orderId,
                                          orderNumber: assignment.orderNumber ?? '',
                                          existing: recorded,
                                        })
                                      }
                                    >
                                      {recorded === undefined
                                        ? t('workspace.deliveries.record')
                                        : t('workspace.deliveries.correct')}
                                    </button>
                                    {recorded !== undefined && (
                                      <button
                                        type="button"
                                        className="btn btn-sm btn-outline-secondary"
                                        disabled={busy}
                                        onClick={() =>
                                          setEvidenceFor({
                                            deliveryId: recorded.id,
                                            orderNumber: assignment.orderNumber ?? '',
                                          })
                                        }
                                      >
                                        {t('workspace.deliveries.attach')}
                                      </button>
                                    )}
                                  </span>
                                )}
                              </li>
                            )
                          })}
                        </ul>
                      )}
                    </li>
                  ))}
                </ol>
              </>
            )}
          </section>

          <section className="tms-card p-3 mb-3">
            <div className="d-flex justify-content-between align-items-start mb-2 gap-2">
              <h2 className="tms-section-title mb-0">
                {t('workspace.sections.problems')}
                {openProblems > 0 && (
                  <span className="ms-2 tms-badge tms-badge-danger">
                    {t('workspace.problems.openCount', { count: openProblems })}
                  </span>
                )}
              </h2>
              {/* Reporting a problem stays available after the trip is closed: these are written
                  up when somebody has time, which is rarely while the truck is still out. The
                  server refuses only a draft trip, which has no operations to report on. */}
              {canExecute && trip.status !== 'DRAFT' && (
                <button
                  type="button"
                  className="btn btn-sm btn-outline-danger"
                  disabled={busy}
                  onClick={() => setProblem({ mode: 'report' })}
                >
                  {t('workspace.problems.report')}
                </button>
              )}
            </div>
            {exceptions.length === 0 ? (
              <p className="text-secondary small mb-0">{t('workspace.problems.empty')}</p>
            ) : (
              <ul className="list-group small">
                {exceptions.map((exception) => (
                  <li key={exception.id} className="list-group-item">
                    <div className="d-flex justify-content-between align-items-start gap-2">
                      <span className="tms-min-w-0">
                        <span className="fw-semibold">
                          {enumLabels.tripExceptionType(exception.exceptionType)}
                        </span>
                        <span className="ms-2">
                          <StatusBadge
                            label={enumLabels.tripExceptionStatus(exception.status)}
                            tone={TRIP_EXCEPTION_TONE[exception.status]}
                          />
                        </span>
                        <span className="d-block text-secondary">
                          {exception.stopSequence === null
                            ? t('workspace.problems.wholeTrip')
                            : t('workspace.timeline.atStop', {
                                sequence: exception.stopSequence,
                                name: exception.stopDestinationName ?? exception.stopDestinationCode ?? '',
                              })}
                          {' · '}
                          {format.dateTime(exception.reportedAt)}
                        </span>
                        {exception.notes !== null && <span className="d-block">{exception.notes}</span>}
                        {exception.resolutionNotes !== null && (
                          <span className="d-block text-success">
                            {t('workspace.problems.resolution')}: {exception.resolutionNotes}
                          </span>
                        )}
                      </span>
                      {canExecute && exception.status === 'OPEN' && (
                        <button
                          type="button"
                          className="btn btn-sm btn-outline-success text-nowrap"
                          disabled={busy}
                          onClick={() => void resolveProblem(exception)}
                        >
                          {t('workspace.problems.resolve')}
                        </button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="tms-card p-3 mb-3">
            <h2 className="tms-section-title mb-2">{t('workspace.sections.timeline')}</h2>
            <TripTimeline
              events={eventsQuery.data ?? []}
              loading={eventsQuery.isPending}
              failed={eventsQuery.isError}
              onRetry={() => void eventsQuery.refetch()}
            />
          </section>

          <section className="tms-card p-3">
            <h2 className="tms-section-title mb-2">{t('workspace.sections.orders')}</h2>
            {assignments.length === 0 ? (
              <p className="text-secondary small mb-0">{t('workspace.noOrders')}</p>
            ) : (
              <ul className="list-group small">
                {assignments.map((assignment) => (
                  <li key={assignment.assignmentId} className="list-group-item d-flex justify-content-between">
                    <span>
                      <span className="fw-semibold">{assignment.orderNumber}</span>
                      <span className="d-block text-secondary">
                        {assignment.destinationName ?? assignment.destinationCode}
                      </span>
                    </span>
                    <span className="text-end text-nowrap">
                      {format.weight(assignment.assignedWeightKg)}
                      <span className="d-block text-secondary">{format.volume(assignment.assignedVolumeM3)}</span>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>

      {problem !== null && (
        <TripProblemDrawer
          title={t(PROBLEM_COPY[problem.mode].title)}
          subtitle={t(PROBLEM_COPY[problem.mode].subtitle)}
          submitLabel={t(PROBLEM_COPY[problem.mode].submit)}
          // Only the trip-level report picks a stop; skipping and failing already have one.
          stops={
            problem.mode === 'report'
              ? stops.map((stop) => ({
                  id: stop.id,
                  label: `${stop.sequence}. ${stop.destinationName ?? stop.destinationCode ?? ''}`,
                }))
              : undefined
          }
          lockedStopId={problem.stopId}
          onClose={() => setProblem(null)}
          onSubmit={submitProblem}
        />
      )}

      {delivery !== null && (
        <DeliveryDrawer
          stopLabel={delivery.stopLabel}
          orderNumber={delivery.orderNumber}
          existing={delivery.existing}
          onClose={() => setDelivery(null)}
          onSubmit={submitDelivery}
        />
      )}

      {evidenceFor !== null && (
        <DeliveryEvidenceDrawer
          orderNumber={evidenceFor.orderNumber}
          onClose={() => setEvidenceFor(null)}
          onSubmit={submitEvidence}
        />
      )}

      {showDriverModal && (
        <TripDriverDrawer
          companyId={companyId}
          trip={trip}
          onClose={() => setShowDriverModal(false)}
          onUpdated={() => {
            setShowDriverModal(false)
            notifySuccess(t('workspace.toasts.driverSaved'), trip.shipmentNumber)
            refresh()
          }}
        />
      )}
    </div>
  )
}
