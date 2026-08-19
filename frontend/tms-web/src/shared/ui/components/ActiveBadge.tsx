import { useTranslation } from 'react-i18next'
import { StatusBadge } from './StatusBadge'

/**
 * The active/inactive badge every master screen shows. Kept as one component rather than a
 * ternary repeated per screen so the label is translated in exactly one place and cannot drift
 * back into an English literal.
 */
export function ActiveBadge({ active }: { active: boolean }) {
  const { t } = useTranslation('statuses')

  return <StatusBadge label={active ? t('active') : t('inactive')} tone={active ? 'success' : 'neutral'} />
}
