import type { FormEvent, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

interface FilterBarProps {
  children: ReactNode
  onSubmit?: () => void
  onReset?: () => void
}

/** Row of filter controls above a data table. Screens compose their own filter fields as
 * children; this only supplies the layout, submit and reset behaviour. */
export function FilterBar({ children, onSubmit, onReset }: FilterBarProps) {
  const { t } = useTranslation('common')

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    onSubmit?.()
  }

  return (
    <form className="card shadow-sm mb-3" onSubmit={handleSubmit}>
      <div className="card-body d-flex flex-wrap align-items-end gap-3 py-2">
        {children}
        {(onSubmit || onReset) && (
          <div className="d-flex gap-2 ms-auto">
            {onReset && (
              <button type="button" className="btn btn-sm btn-outline-secondary" onClick={onReset}>
                {t('actions.clear')}
              </button>
            )}
            {onSubmit && (
              <button type="submit" className="btn btn-sm btn-primary">
                {t('actions.applyFilters')}
              </button>
            )}
          </div>
        )}
      </div>
    </form>
  )
}
