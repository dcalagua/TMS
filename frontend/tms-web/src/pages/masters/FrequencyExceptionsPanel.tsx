import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  createFrequencyException,
  deleteFrequencyException,
  fetchFrequencyExceptions,
} from '../../shared/api/frequenciesApi'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { confirmDialog, StatusBadge } from '../../shared/ui/components'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'

interface FrequencyExceptionsPanelProps {
  companyId: string
  frequencyId: string
  canManage: boolean
}

/**
 * Date exceptions on a service calendar: the two answers the weekly cadence cannot give.
 *
 * A weekly rule says "Mondays, Wednesdays and Fridays". Christmas Day is a Wednesday and the
 * depot is shut; the Saturday before it, everyone works. Both are exceptions to the cadence and
 * neither can be expressed by editing it - changing the rule would change every week.
 *
 * Two kinds, because the model has exactly two (`frequency_exception.service_override`):
 * **cerrado** removes a date the cadence would have served, **abierto** adds one it would not.
 * Anything more elaborate - a different cutoff for one date, say - is not representable yet, and
 * the help text says so rather than letting an operator discover it.
 *
 * A sub-resource with its own endpoints, so it is its own panel and not fields on the frequency
 * form: each row here is one API call, and it needs a saved frequency to hang from - the same
 * shape `LocationFrequencyPanel` uses.
 */
export function FrequencyExceptionsPanel({ companyId, frequencyId, canManage }: FrequencyExceptionsPanelProps) {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const queryClient = useQueryClient()

  const [date, setDate] = useState('')
  const [kind, setKind] = useState<'closed' | 'open'>('closed')
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)

  const exceptionsQuery = useQuery({
    queryKey: ['frequency-exceptions', companyId, frequencyId],
    queryFn: ({ signal }) => fetchFrequencyExceptions(companyId, frequencyId, signal),
  })
  const exceptions = exceptionsQuery.data ?? []

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['frequency-exceptions', companyId, frequencyId] })
  }

  async function add() {
    if (date === '') return
    setSaving(true)
    try {
      await createFrequencyException(companyId, frequencyId, {
        exceptionDate: date,
        serviceOverride: kind === 'open',
        note: note.trim() === '' ? null : note.trim(),
      })
      setDate('')
      setNote('')
      notifySuccess(td('created'))
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    } finally {
      setSaving(false)
    }
  }

  async function remove(exceptionId: string, exceptionDate: string) {
    const confirmed = await confirmDialog({
      title: td('delete.title', { name: exceptionDate }),
      text: td('delete.text'),
      confirmLabel: tc('actions.delete'),
      dangerous: true,
    })
    if (!confirmed) return

    try {
      await deleteFrequencyException(companyId, frequencyId, exceptionId)
      notifySuccess(td('deleted'))
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  return (
    <>
      <p className="text-body-secondary small">{t('frequencies.form.exceptionsHelp')}</p>

      {exceptionsQuery.isError && (
        <div className="alert alert-danger py-2 small" role="alert">
          {describeApiError(exceptionsQuery.error as ApiError)}
        </div>
      )}

      {exceptions.length === 0 ? (
        <p className="text-body-secondary small">{t('frequencies.form.noExceptions')}</p>
      ) : (
        <div className="tms-table-scroll mb-3">
          <table className="table table-sm align-middle mb-0">
            <caption className="visually-hidden">{t('frequencies.form.exceptions')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('frequencies.form.exceptionDate')}</th>
                <th scope="col">{t('frequencies.form.exceptionKind')}</th>
                <th scope="col">{t('frequencies.form.exceptionNote')}</th>
                {canManage && <th scope="col" className="text-end">{tc('columns.actions')}</th>}
              </tr>
            </thead>
            <tbody>
              {exceptions.map((exception) => (
                <tr key={exception.id}>
                  <td className="tms-code">{exception.exceptionDate}</td>
                  <td>
                    <StatusBadge
                      label={exception.serviceOverride
                        ? t('frequencies.form.exceptionOpen')
                        : t('frequencies.form.exceptionClosed')}
                      tone={exception.serviceOverride ? 'success' : 'danger'}
                    />
                  </td>
                  <td className="small">{exception.note ?? '—'}</td>
                  {canManage && (
                    <td className="text-end">
                      <button
                        type="button"
                        className="btn btn-sm btn-outline-danger"
                        aria-label={t('frequencies.form.removeException', { date: exception.exceptionDate })}
                        onClick={() => void remove(exception.id, exception.exceptionDate)}
                      >
                        <i className="bi bi-trash" aria-hidden="true" />
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {canManage && (
        <div className="row g-2 align-items-end">
          <div className="col-12 col-sm-4">
            <FormField label={t('frequencies.form.exceptionDate')} htmlFor="frequency-exception-date">
              <input
                id="frequency-exception-date"
                type="date"
                className="form-control form-control-sm"
                value={date}
                onChange={(event) => setDate(event.target.value)}
              />
            </FormField>
          </div>
          <div className="col-12 col-sm-4">
            <FormField label={t('frequencies.form.exceptionKind')} htmlFor="frequency-exception-kind">
              <Select
                id="frequency-exception-kind"
                size="sm"
                value={kind}
                onChange={(next) => setKind(next as 'closed' | 'open')}
                options={[
                  { value: 'closed', label: t('frequencies.form.exceptionClosed') },
                  { value: 'open', label: t('frequencies.form.exceptionOpen') },
                ]}
              />
            </FormField>
          </div>
          <div className="col-12 col-sm-4">
            <FormField label={t('frequencies.form.exceptionNote')} htmlFor="frequency-exception-note">
              <input
                id="frequency-exception-note"
                type="text"
                className="form-control form-control-sm"
                maxLength={500}
                value={note}
                onChange={(event) => setNote(event.target.value)}
              />
            </FormField>
          </div>
          <div className="col-12">
            <button
              type="button"
              className="btn btn-sm btn-outline-primary d-inline-flex align-items-center gap-2"
              disabled={date === '' || saving}
              onClick={() => void add()}
            >
              <i className="bi bi-plus-lg" aria-hidden="true" />
              {t('frequencies.form.addException')}
            </button>
          </div>
        </div>
      )}
    </>
  )
}
