import type { FormEvent, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

interface FilterBarProps {
  children: ReactNode
  onSubmit?: () => void
  onReset?: () => void
}

/**
 * The filter strip above a data table. Screens compose their own fields as children; this owns
 * the layout, the submit and the reset.
 *
 * It is one bar rather than a Bootstrap card wrapping a row of inputs. Each field claims an
 * equal share of the row, the actions sit at the end behind a divider, and the strip carries
 * the same border and radius as the table panel beneath it - so filtering reads as part of the
 * list, not as a separate box that happens to be above it.
 */
export function FilterBar({ children, onSubmit, onReset }: FilterBarProps) {
  const { t } = useTranslation('common')

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    onSubmit?.()
  }

  return (
    <form className="tms-filterbar" onSubmit={handleSubmit}>
      {children}
      {(onSubmit || onReset) && (
        <div className="tms-filterbar-actions">
          {onReset && (
            <button type="button" className="btn btn-sm btn-outline-secondary" onClick={onReset}>
              {t('actions.clear')}
            </button>
          )}
          {onSubmit && (
            <button type="submit" className="btn btn-sm btn-primary d-flex align-items-center gap-2">
              <i className="bi bi-search" aria-hidden="true" />
              {t('actions.applyFilters')}
            </button>
          )}
        </div>
      )}
    </form>
  )
}
