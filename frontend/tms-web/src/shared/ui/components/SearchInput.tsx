import { useId } from 'react'
import { useTranslation } from 'react-i18next'

export interface SearchInputProps {
  value: string
  onChange: (value: string) => void
  /** Overrides the placeholder and accessible name; defaults to a generic "Search". */
  label?: string
  onClear?: () => void
}

/** Single prominent search field for a list screen's toolbar. Always labelled: a placeholder
 * disappears the moment someone types, and then the field has no accessible name at all. */
export function SearchInput({ value, onChange, label, onClear }: SearchInputProps) {
  const { t } = useTranslation('common')
  const id = useId()
  const text = label ?? t('actions.search')

  return (
    <div className="tms-search">
      <label htmlFor={id} className="visually-hidden">
        {text}
      </label>
      <i className="bi bi-search tms-search-icon" aria-hidden="true" />
      <input
        id={id}
        type="search"
        className="form-control form-control-sm"
        placeholder={text}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape' && value) {
            event.preventDefault()
            onChange('')
            onClear?.()
          }
        }}
      />
    </div>
  )
}
