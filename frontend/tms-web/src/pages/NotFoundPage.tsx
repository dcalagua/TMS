import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'

export function NotFoundPage() {
  const { t } = useTranslation('common')

  return (
    <div className="text-center py-5">
      <p className="display-6 mb-2">{t('notFound.code')}</p>
      <p className="text-body-secondary">{t('notFound.message')}</p>
      <Link className="btn btn-primary btn-sm" to="/">
        {t('notFound.back')}
      </Link>
    </div>
  )
}
