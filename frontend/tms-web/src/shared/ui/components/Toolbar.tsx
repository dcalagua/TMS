import { useId, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

export interface ToolbarProps {
  /** Always visible: the search field and the primary action. */
  primary?: ReactNode
  /** The secondary filter controls, collapsible below `md`. */
  filters?: ReactNode
  onApply?: () => void
  onReset?: () => void
  /** Shown next to the filter toggle, e.g. "3 filters applied". */
  activeFilterCount?: number
}

/**
 * The control strip above a list. On a wide screen the filters sit on one line next to the
 * search field; below `md` they collapse behind a toggle, because five stacked inputs between
 * an operator and their results is not a filter bar, it is a wall.
 */
export function Toolbar({ primary, filters, onApply, onReset, activeFilterCount = 0 }: ToolbarProps) {
  const { t } = useTranslation('common')
  const [expanded, setExpanded] = useState(false)
  const panelId = useId()

  return (
    <div className="tms-toolbar mb-3">
      <div className="d-flex flex-wrap align-items-center gap-2 w-100">
        {primary}

        {filters && (
          <button
            type="button"
            className="btn btn-sm btn-outline-secondary d-md-none ms-auto d-inline-flex align-items-center gap-1"
            onClick={() => setExpanded((open) => !open)}
            aria-expanded={expanded}
            aria-controls={panelId}
          >
            <i className="bi bi-funnel" aria-hidden="true" />
            <span>{t('actions.applyFilters')}</span>
            {activeFilterCount > 0 && <span className="badge text-bg-primary">{activeFilterCount}</span>}
          </button>
        )}
      </div>

      {filters && (
        <form
          id={panelId}
          className={`w-100 ${expanded ? 'd-flex' : 'd-none'} d-md-flex flex-wrap align-items-end gap-2`}
          onSubmit={(event) => {
            event.preventDefault()
            onApply?.()
          }}
        >
          {filters}

          <div className="d-flex gap-2 ms-md-auto">
            {onReset && (
              <button type="button" className="btn btn-sm btn-outline-secondary" onClick={onReset}>
                {t('actions.clear')}
              </button>
            )}
            {onApply && (
              <button type="submit" className="btn btn-sm btn-primary">
                {t('actions.applyFilters')}
              </button>
            )}
          </div>
        </form>
      )}
    </div>
  )
}
