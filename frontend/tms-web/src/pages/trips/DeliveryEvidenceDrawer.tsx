import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { EVIDENCE_TYPES, type EvidenceType } from '../../shared/api/planningApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'delivery-evidence-form'

export interface DeliveryEvidenceValues {
  evidenceType: EvidenceType
  /** A `datetime-local` value, or null. The caller converts it to the ISO instant the API takes. */
  capturedAt: string | null
  file: File
}

interface DeliveryEvidenceDrawerProps {
  orderNumber: string
  onClose: () => void
  onSubmit: (values: DeliveryEvidenceValues) => Promise<void>
}

/**
 * Attaching a signature, a photo or a signed note to a delivery that has already been recorded.
 *
 * <p>Plain state rather than `react-hook-form`, unlike its siblings: the form is one file and two
 * fields, and a file input is the one control whose value a form library cannot own anyway.
 *
 * <p>The accepted types are the server's ({@code tms.storage.evidence.allowed-content-types}) and
 * the `accept` attribute below is only a hint to the file picker - a deployment that narrows the
 * list is enforced server-side, and a refusal comes back as a message in this drawer rather than as
 * a toast behind it. The same is true of a deployment with no store configured at all: the upload
 * answers 503 and the sentence explains that delivery results are still recorded without it.
 */
export function DeliveryEvidenceDrawer({ orderNumber, onClose, onSubmit }: DeliveryEvidenceDrawerProps) {
  const { t } = useTranslation('trips')
  const { t: tc } = useTranslation('common')
  const enumLabels = useEnumLabels()

  const [evidenceType, setEvidenceType] = useState<EvidenceType>('SIGNATURE')
  const [capturedAt, setCapturedAt] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    if (file === null) {
      setFormError(t('workspace.evidence.fileRequired'))
      return
    }
    setBusy(true)
    try {
      await onSubmit({ evidenceType, capturedAt: capturedAt.trim() === '' ? null : capturedAt, file })
    } catch (error) {
      setFormError((error as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <TmsDrawer
      open
      title={t('workspace.evidence.title')}
      subtitle={t('workspace.evidence.subtitle', { order: orderNumber })}
      size="md"
      onClose={onClose}
      dirty={file !== null}
      closeOnEscape={!busy}
      closeOnBackdrop={!busy}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>
            {tc('actions.cancel')}
          </button>
          <button type="submit" form={FORM_ID} className="btn btn-primary" disabled={busy}>
            {busy ? tc('actions.saving') : t('workspace.evidence.submit')}
          </button>
        </>
      }
    >
      <form id={FORM_ID} onSubmit={(event) => void submit(event)} noValidate>
        {formError && (
          <div className="alert alert-danger py-2 small" role="alert">
            {formError}
          </div>
        )}

        <FormField label={t('workspace.evidence.type')} htmlFor="evidence-type" required>
          <Select
            id="evidence-type"
            value={evidenceType}
            onChange={(next) => setEvidenceType(next as EvidenceType)}
            options={EVIDENCE_TYPES.map((value) => ({ value, label: enumLabels.evidenceType(value) }))}
          />
        </FormField>

        <FormField label={t('workspace.evidence.file')} htmlFor="evidence-file" required
          help={t('workspace.evidence.fileHelp')}>
          <input
            id="evidence-file"
            type="file"
            className="form-control"
            accept="image/jpeg,image/png,image/webp,application/pdf"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
        </FormField>

        <FormField
          label={t('workspace.evidence.capturedAt')}
          htmlFor="evidence-captured-at"
          help={t('workspace.evidence.capturedAtHelp')}
        >
          <input
            id="evidence-captured-at"
            type="datetime-local"
            className="form-control"
            value={capturedAt}
            onChange={(event) => setCapturedAt(event.target.value)}
          />
        </FormField>
      </form>
    </TmsDrawer>
  )
}
