import type {
  DeliveryResult,
  StopExecutionStatus,
  TripExceptionStatus,
  TripStatus,
} from '../api/planningApi'
import type { TenderStatus } from '../api/tendersApi'
import type { StatusTone } from './components'

/**
 * How each lifecycle state is coloured, in one place so the list and the workspace never disagree
 * about what "in transit" looks like.
 *
 * `IN_TRANSIT` is `warning` rather than `success`: a trip on the road is not a finished trip, and
 * the amber is what makes the day's outstanding work visible at a glance on a board of 300 rows.
 * Colour is never the only signal - `StatusBadge` always carries the translated label too.
 */
export const TRIP_STATUS_TONE: Record<TripStatus, StatusTone> = {
  DRAFT: 'neutral',
  CONFIRMED: 'info',
  READY_FOR_DISPATCH: 'info',
  IN_TRANSIT: 'warning',
  COMPLETED: 'success',
  CANCELLED: 'danger',
}

/**
 * How each stop outcome is coloured.
 *
 * `SKIPPED` is amber and `FAILED` is red, deliberately not the same: a stop dropped by decision is
 * a planned outcome, while one that was attempted and refused is the thing somebody has to call
 * the customer about. Merging the two into one colour would be merging the two facts.
 */
export const STOP_EXECUTION_TONE: Record<StopExecutionStatus, StatusTone> = {
  PENDING: 'neutral',
  ARRIVED: 'info',
  IN_SERVICE: 'info',
  COMPLETED: 'success',
  SKIPPED: 'warning',
  FAILED: 'danger',
}

/** Open problems are the ones that still need somebody; resolved ones are history. */
export const TRIP_EXCEPTION_TONE: Record<TripExceptionStatus, StatusTone> = {
  OPEN: 'danger',
  RESOLVED: 'success',
}

/**
 * How each delivery outcome is coloured (migration V28).
 *
 * `NOT_ATTEMPTED` is neutral rather than red: nothing went wrong with *the goods*, the stop simply
 * was not served - and the stop's own badge is already carrying that news. `PARTIAL` is amber
 * because somebody has to chase the rest, and `REJECTED`/`FAILED` are red because somebody has to
 * ring the customer. Colour is never the only signal; `StatusBadge` carries the translated label.
 */
export const DELIVERY_RESULT_TONE: Record<DeliveryResult, StatusTone> = {
  DELIVERED: 'success',
  PARTIAL: 'warning',
  REJECTED: 'danger',
  FAILED: 'danger',
  NOT_ATTEMPTED: 'neutral',
}

/**
 * How each tender attempt is coloured (migration V31).
 *
 * `SENT` is amber and not blue, unlike `CONFIRMED` above: an offer waiting for an answer is the
 * dispatcher's outstanding work, and it is the state that quietly turns into a problem if the truck
 * has to leave before anybody replies. `EXPIRED` is amber for the same reason and not red - nobody
 * did anything wrong, a clock ran out - while `REJECTED` is red, because a carrier saying no is the
 * thing somebody has to act on today. `CANCELLED` is neutral: we withdrew it on purpose.
 */
export const TENDER_STATUS_TONE: Record<TenderStatus, StatusTone> = {
  DRAFT: 'neutral',
  SENT: 'warning',
  ACCEPTED: 'success',
  REJECTED: 'danger',
  EXPIRED: 'warning',
  CANCELLED: 'neutral',
}
