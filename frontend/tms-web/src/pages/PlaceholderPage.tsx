import { useTranslation } from 'react-i18next'
import { EmptyState } from '../shared/ui/components/EmptyState'
import type { NavigationKey } from '../shared/i18n/keys'
import { PageHeader } from '../shared/ui/components/PageHeader'

/** Clean placeholder for a navigable module that has no screen yet. Never fakes data or
 * functionality - it says plainly that the module is not built yet.
 *
 * Takes a key rather than text so the route table, which cannot call hooks, still produces a
 * translated screen. */
export function PlaceholderPage({ titleKey }: { titleKey: NavigationKey }) {
  const { t } = useTranslation(['navigation', 'common'])
  const title = t(titleKey, { ns: 'navigation' })

  return (
    <>
      <PageHeader title={title} />
      <EmptyState
        title={t('comingSoon.title', { ns: 'common' })}
        message={t('comingSoon.message', { ns: 'common', module: title })}
      />
    </>
  )
}
