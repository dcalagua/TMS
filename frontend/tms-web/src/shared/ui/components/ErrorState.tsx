import { useTranslation } from 'react-i18next'

interface ErrorStateProps {
  title?: string
  message: string
  correlationId?: string | null
  onRetry?: () => void
}

/** Standard in-place error panel. Screens pass a friendly `message` (see
 * `shared/api/problemMessages.ts`), never a raw `error.message`/`detail`. */
export function ErrorState({ title, message, correlationId, onRetry }: ErrorStateProps) {
  const { t } = useTranslation('common')

  return (
    <div className="alert alert-danger" role="alert">
      <div className="fw-semibold">{title ?? t('states.errorTitle')}</div>
      <p className="mb-2 small">{message}</p>
      {correlationId && (
        <p className="mb-2 small text-body-secondary">
          {t('labels.reference')}: {correlationId}
        </p>
      )}
      {onRetry && (
        <button type="button" className="btn btn-sm btn-outline-danger" onClick={onRetry}>
          {t('actions.tryAgain')}
        </button>
      )}
    </div>
  )
}
