import { EmptyState } from '../shared/ui/components/EmptyState'
import { PageHeader } from '../shared/ui/components/PageHeader'

/** Clean placeholder for a navigable module that has no screen yet. Never fakes data or
 * functionality - it says plainly that the module is not built yet. */
export function PlaceholderPage({ title }: { title: string }) {
  return (
    <>
      <PageHeader title={title} />
      <EmptyState title="Coming soon" message={`${title} will be available in an upcoming release.`} />
    </>
  )
}
