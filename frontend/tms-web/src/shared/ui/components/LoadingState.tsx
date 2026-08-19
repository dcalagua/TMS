/** Standard in-place loading indicator for a panel, table or full page section. */
export function LoadingState({ label = 'Loading...' }: { label?: string }) {
  return (
    <div className="d-flex align-items-center justify-content-center gap-2 text-body-secondary py-5" role="status">
      <span className="spinner-border spinner-border-sm" aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}
