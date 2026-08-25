import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

interface RevealedField {
  label: string
  value: string
  /** A secret is masked until the operator asks to see it; a client id is not. */
  secret: boolean
}

interface SecretRevealDrawerProps {
  title: string
  /** The backend's own notice, so the warning and the API contract cannot drift apart. */
  notice: string
  fields: RevealedField[]
  onClose: () => void
}

/**
 * The one screen in the product that displays a secret, once.
 *
 * Everything about it is shaped by that: the value is masked until asked for, copying is one click
 * because the alternative is somebody transcribing 43 base64 characters by hand, and closing it is
 * deliberate rather than incidental - there is no backdrop dismissal, because losing this by
 * clicking beside it means rotating and reconfiguring the partner's system.
 *
 * It is generic over both kinds of secret - an inbound credential and a webhook signing key -
 * because the handling rules are identical and a second copy of this screen would be a second place
 * to get them wrong.
 */
export function SecretRevealDrawer({ title, notice, fields, onClose }: SecretRevealDrawerProps) {
  const { t } = useTranslation('settings')
  const { t: tc } = useTranslation('common')
  const [revealed, setRevealed] = useState<Record<string, boolean>>({})
  const [copied, setCopied] = useState<string | null>(null)

  async function copy(field: RevealedField) {
    try {
      await navigator.clipboard.writeText(field.value)
      setCopied(field.label)
    } catch {
      // A denied clipboard permission is not an error worth a dialog: the value is on screen and
      // can be selected. Revealing it is the useful fallback.
      setRevealed((current) => ({ ...current, [field.label]: true }))
    }
  }

  return (
    <TmsDrawer
      open
      title={title}
      subtitle={t('integrations.secret.subtitle')}
      size="md"
      onClose={onClose}
      closeOnBackdrop={false}
      footer={
        <button type="button" className="btn btn-primary" onClick={onClose}>
          {t('integrations.secret.done')}
        </button>
      }
    >
      <div className="alert alert-warning" role="alert">
        <i className="bi bi-exclamation-triangle me-2" aria-hidden="true" />
        {notice}
      </div>

      {fields.map((field) => {
        const isRevealed = !field.secret || revealed[field.label] === true
        return (
          <div className="mb-3" key={field.label}>
            <label className="form-label small fw-semibold mb-1">{field.label}</label>
            <div className="input-group input-group-sm">
              <input
                readOnly
                className="form-control font-monospace"
                aria-label={field.label}
                value={isRevealed ? field.value : '•'.repeat(Math.min(field.value.length, 48))}
              />
              {field.secret && (
                <button
                  type="button"
                  className="btn btn-outline-secondary"
                  aria-label={isRevealed ? t('integrations.secret.hide') : t('integrations.secret.reveal')}
                  onClick={() => setRevealed((current) => ({ ...current, [field.label]: !isRevealed }))}
                >
                  <i className={`bi ${isRevealed ? 'bi-eye-slash' : 'bi-eye'}`} aria-hidden="true" />
                </button>
              )}
              <button
                type="button"
                className="btn btn-outline-secondary"
                aria-label={tc('actions.copy')}
                onClick={() => void copy(field)}
              >
                <i className="bi bi-clipboard" aria-hidden="true" />
              </button>
            </div>
            {copied === field.label && (
              <span className="form-text text-success">{t('integrations.secret.copied')}</span>
            )}
          </div>
        )
      })}
    </TmsDrawer>
  )
}
