import { useTranslation } from 'react-i18next'

/** Standard in-place loading indicator for a panel, table or full page section. */
export function LoadingState({ label }: { label?: string }) {
  const { t } = useTranslation('common')

  return (
    <div className="d-flex align-items-center justify-content-center gap-2 text-body-secondary py-5" role="status">
      <span className="spinner-border spinner-border-sm" aria-hidden="true" />
      <span>{label ?? t('states.loading')}</span>
    </div>
  )
}
