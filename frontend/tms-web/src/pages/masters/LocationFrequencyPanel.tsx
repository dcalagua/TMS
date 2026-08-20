import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchFrequencies } from '../../shared/api/frequenciesApi'
import {
  activateLocationFrequency,
  createLocationFrequency,
  deactivateLocationFrequency,
  deleteLocationFrequency,
  fetchLocationEligibility,
  fetchLocationFrequencies,
  type EligibilityView,
} from '../../shared/api/locationFrequenciesApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { ActiveBadge, confirmDialog } from '../../shared/ui/components'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'

interface LocationFrequencyPanelProps {
  companyId: string
  locationId: string
  locationName: string
}

/** Today, as `YYYY-MM-DD`, for the eligibility date input's default value. */
function today(): string {
  return new Date().toISOString().slice(0, 10)
}

/**
 * A location's service calendar (migration V15/job 03): which frequencies govern whether it can
 * be dispatched to or serviced on a given date, plus a quick eligibility check against that
 * calendar. Only rendered once the location has an id - the associations are a sub-resource of
 * an existing location, the same reason `FrequencyController`'s exceptions sub-resource requires
 * a saved frequency first.
 *
 * Kept as its own panel rather than form fields, because every mutation here (add/remove/
 * activate/deactivate an association) is its own API call against its own sub-resource, not part
 * of the location's own create/update payload - the same shape `FrequencyFormDrawer` would use
 * for exceptions if that sub-resource were wired into the UI yet.
 */
export function LocationFrequencyPanel({ companyId, locationId, locationName }: LocationFrequencyPanelProps) {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const queryClient = useQueryClient()

  const [selectedFrequencyId, setSelectedFrequencyId] = useState('')
  const [effectiveFrom, setEffectiveFrom] = useState('')
  const [effectiveTo, setEffectiveTo] = useState('')
  const [adding, setAdding] = useState(false)

  const [eligibilityDate, setEligibilityDate] = useState(today)
  const [eligibility, setEligibility] = useState<EligibilityView | null>(null)
  const [checkingEligibility, setCheckingEligibility] = useState(false)

  const associationsQuery = useQuery({
    queryKey: ['location-frequencies', companyId, locationId],
    queryFn: () => fetchLocationFrequencies(companyId, locationId),
  })
  const associations = associationsQuery.data ?? []

  const frequenciesQuery = useQuery({
    queryKey: ['frequencies-for-location-form', companyId],
    queryFn: ({ signal }) => fetchFrequencies({ companyId, size: 200, active: true, sort: 'name,asc', signal }),
  })
  const frequencyOptions = frequenciesQuery.data?.content ?? []

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['location-frequencies', companyId, locationId] })
  }

  async function handleAdd() {
    if (!selectedFrequencyId) return
    setAdding(true)
    try {
      await createLocationFrequency(companyId, locationId, {
        frequencyId: selectedFrequencyId,
        effectiveFrom: effectiveFrom || null,
        effectiveTo: effectiveTo || null,
      })
      notifySuccess(td('created'))
      setSelectedFrequencyId('')
      setEffectiveFrom('')
      setEffectiveTo('')
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    } finally {
      setAdding(false)
    }
  }

  async function handleToggleActive(associationId: string, active: boolean, frequencyName: string | null) {
    const label = frequencyName ?? locationName
    const confirmed = await confirmDialog({
      title: active ? td('deactivate.title', { name: label }) : td('activate.title', { name: label }),
      text: active ? td('deactivate.text') : td('activate.text'),
      confirmLabel: active ? tc('actions.deactivate') : tc('actions.activate'),
      dangerous: active,
    })
    if (!confirmed) return

    try {
      if (active) {
        await deactivateLocationFrequency(companyId, locationId, associationId)
        notifySuccess(td('deactivated'))
      } else {
        await activateLocationFrequency(companyId, locationId, associationId)
        notifySuccess(td('activated'))
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  async function handleDelete(associationId: string, frequencyName: string | null) {
    const confirmed = await confirmDialog({
      title: td('delete.title', { name: frequencyName ?? locationName }),
      text: td('delete.text'),
      confirmLabel: td('delete.confirm'),
      dangerous: true,
    })
    if (!confirmed) return

    try {
      await deleteLocationFrequency(companyId, locationId, associationId)
      notifySuccess(td('deleted'))
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  async function handleCheckEligibility() {
    if (!eligibilityDate) return
    setCheckingEligibility(true)
    try {
      setEligibility(await fetchLocationEligibility(companyId, locationId, eligibilityDate))
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    } finally {
      setCheckingEligibility(false)
    }
  }

  // The evaluator only ever matches one of this location's own associations (see
  // `LocationEligibilityEvaluator`), so the match is always findable in `associations`.
  const matchedAssociation = eligibility?.frequencyId
    ? associations.find((association) => association.frequencyId === eligibility.frequencyId)
    : null

  return (
    <>
      <fieldset className="tms-fieldset">
        <legend className="tms-fieldset-legend">{t('locations.form.sectionServiceCalendar')}</legend>
        <p className="text-body-secondary small">{t('locations.form.serviceCalendarHelp')}</p>

        {associations.length === 0 && (
          <p className="text-body-secondary small">{t('locations.form.noAssociations')}</p>
        )}
        {associations.length > 0 && (
          <ul className="list-group mb-3">
            {associations.map((association) => (
              <li
                key={association.id}
                className="list-group-item d-flex justify-content-between align-items-center gap-2 flex-wrap"
              >
                <div className="tms-min-w-0">
                  <div className="tms-truncate">
                    {association.frequencyCode
                      ? `${association.frequencyCode} — ${association.frequencyName}`
                      : association.frequencyId}
                  </div>
                  <div className="text-body-secondary small">
                    {tc('fields.effectiveFrom')}: {association.effectiveFrom ?? '—'} · {tc('fields.effectiveTo')}:{' '}
                    {association.effectiveTo ?? '—'}
                  </div>
                </div>
                <div className="d-flex align-items-center gap-2 flex-shrink-0">
                  <ActiveBadge active={association.active} />
                  <button
                    type="button"
                    className="btn btn-sm btn-outline-secondary"
                    onClick={() => void handleToggleActive(association.id, association.active, association.frequencyName)}
                  >
                    {association.active ? tc('actions.deactivate') : tc('actions.activate')}
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm btn-outline-danger"
                    onClick={() => void handleDelete(association.id, association.frequencyName)}
                  >
                    {tc('actions.delete')}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}

        <div className="row g-2 align-items-end">
          <div className="col-12 col-sm-5">
            <FormField label={tc('fields.frequency')} htmlFor="location-frequency-to-add">
              <Select
                id="location-frequency-to-add"
                value={selectedFrequencyId}
                onChange={setSelectedFrequencyId}
                placeholder={t('locations.form.selectFrequency')}
                options={frequencyOptions.map((frequency) => ({ value: frequency.id, label: frequency.name }))}
              />
            </FormField>
          </div>
          <div className="col-6 col-sm-3">
            <FormField label={tc('fields.effectiveFrom')} htmlFor="location-frequency-from">
              <input
                id="location-frequency-from"
                type="date"
                className="form-control"
                value={effectiveFrom}
                onChange={(event) => setEffectiveFrom(event.target.value)}
              />
            </FormField>
          </div>
          <div className="col-6 col-sm-3">
            <FormField label={tc('fields.effectiveTo')} htmlFor="location-frequency-to">
              <input
                id="location-frequency-to"
                type="date"
                className="form-control"
                value={effectiveTo}
                onChange={(event) => setEffectiveTo(event.target.value)}
              />
            </FormField>
          </div>
          <div className="col-12 col-sm-1">
            <button
              type="button"
              className="btn btn-outline-primary w-100"
              disabled={!selectedFrequencyId || adding}
              onClick={() => void handleAdd()}
              aria-label={t('locations.form.addAssociation')}
              title={t('locations.form.addAssociation')}
            >
              <i className="bi bi-plus-lg" aria-hidden="true" />
            </button>
          </div>
        </div>
      </fieldset>

      <fieldset className="tms-fieldset mb-0">
        <legend className="tms-fieldset-legend">{t('locations.form.sectionEligibility')}</legend>
        <p className="text-body-secondary small">{t('locations.form.eligibilityHelp')}</p>
        <div className="row g-2 align-items-end">
          <div className="col-6 col-sm-4">
            <FormField label={t('locations.form.eligibilityDateLabel')} htmlFor="location-eligibility-date">
              <input
                id="location-eligibility-date"
                type="date"
                className="form-control"
                value={eligibilityDate}
                onChange={(event) => setEligibilityDate(event.target.value)}
              />
            </FormField>
          </div>
          <div className="col-6 col-sm-3">
            <button
              type="button"
              className="btn btn-outline-secondary w-100"
              disabled={!eligibilityDate || checkingEligibility}
              onClick={() => void handleCheckEligibility()}
            >
              {t('locations.form.eligibilityCheck')}
            </button>
          </div>
        </div>

        {eligibility && (
          <div className={`alert py-2 mt-3 ${eligibility.eligible ? 'alert-success' : 'alert-secondary'}`} role="status">
            <strong>
              {eligibility.eligible ? t('locations.form.eligibilityEligible') : t('locations.form.eligibilityNotEligible')}
            </strong>
            {eligibility.eligible && matchedAssociation && (
              <div className="small">
                {tc('fields.frequency')}: {matchedAssociation.frequencyCode} — {matchedAssociation.frequencyName}
              </div>
            )}
            {eligibility.eligible && (eligibility.cutoffTime || eligibility.leadTimeDays !== null) && (
              <div className="small">
                {eligibility.cutoffTime && (
                  <span className="me-3">
                    {t('frequencies.form.cutoffTime')}: {eligibility.cutoffTime}
                  </span>
                )}
                {eligibility.leadTimeDays !== null && (
                  <span>
                    {t('frequencies.form.leadTimeDays')}: {eligibility.leadTimeDays}
                  </span>
                )}
              </div>
            )}
          </div>
        )}
      </fieldset>
    </>
  )
}
