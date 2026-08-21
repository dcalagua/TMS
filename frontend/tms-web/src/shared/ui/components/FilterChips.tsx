import { useTranslation } from 'react-i18next'

export interface FilterChip {
  key: string
  /** What the filter is, e.g. "Tipo". */
  label: string
  /** What it is set to, already translated - never a raw enum value or a code. */
  value: string
  /** Clears this one filter. Omit for a filter that cannot be cleared on its own. */
  onClear?: () => void
}

interface FilterChipsProps {
  chips: FilterChip[]
  /** Clears everything. Rendered only when more than one chip is showing. */
  onClearAll?: () => void
}

/**
 * The filters currently narrowing the list, as removable chips.
 *
 * A filter bar keeps its values in its own inputs, which are three lines above the result count
 * and easy to scroll past. When a list looks empty or short, "what am I filtering by?" is the
 * first question, and this answers it without the user re-reading four controls - and lets them
 * drop one filter without hunting for which control held it.
 *
 * Rendered only when something is actually applied, so an unfiltered list carries no extra row.
 */
export function FilterChips({ chips, onClearAll }: FilterChipsProps) {
  const { t } = useTranslation('common')

  if (chips.length === 0) {
    return null
  }

  return (
    <div className="tms-filter-chips">
      <span className="tms-filter-chips-label">{t('filters.applied')}</span>
      {chips.map((chip) => (
        <span key={chip.key} className="tms-filter-chip">
          <span className="tms-filter-chip-label">{chip.label}:</span>
          <span className="tms-filter-chip-value tms-truncate">{chip.value}</span>
          {chip.onClear && (
            <button
              type="button"
              className="tms-filter-chip-clear"
              onClick={chip.onClear}
              aria-label={t('filters.clearOne', { filter: chip.label })}
            >
              <i className="bi bi-x" aria-hidden="true" />
            </button>
          )}
        </span>
      ))}
      {onClearAll && chips.length > 1 && (
        <button type="button" className="tms-filter-chips-clear-all" onClick={onClearAll}>
          {t('filters.clearAll')}
        </button>
      )}
    </div>
  )
}
