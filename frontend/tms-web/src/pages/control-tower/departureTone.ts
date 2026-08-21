import type { DepartureTimeliness } from '../../shared/api/controlTowerApi'
import type { StatusTone } from '../../shared/ui/components'

/**
 * The colour of each departure verdict.
 *
 * Kept beside the screen rather than in the API client for the same reason `tripStatus.ts` is:
 * the value is contract and the colour is presentation, and the two must be free to change
 * independently.
 *
 * Only the two problems take a warm tone. `ON_TIME` is deliberately neutral rather than green -
 * on a board where most rows are on time, colouring them all would leave the eye nothing to find,
 * which is the failure mode a control tower cannot afford. `NOT_SCHEDULED` is a warning and not a
 * neutral: a trip planned for today with no departure time is a gap somebody has to close, not a
 * quiet default.
 */
export const DEPARTURE_TONE: Record<DepartureTimeliness, StatusTone> = {
  LATE: 'danger',
  OVERDUE: 'danger',
  NOT_SCHEDULED: 'warning',
  SCHEDULED: 'info',
  ON_TIME: 'neutral',
  NOT_APPLICABLE: 'neutral',
}
