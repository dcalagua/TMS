import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'

/** One selectable master record. `code` and `name` are shown together because operators know
 * some records by one and some by the other. */
export interface LookupOption {
  id: string
  code: string
  name: string
}

export interface LookupFieldProps {
  /** Put the same id on the field's `<label htmlFor>`. */
  id: string
  /** The selected record's id, or `''` for nothing selected. */
  value: string
  /** How the current selection reads while the field is not being edited. Supplied by the
   * caller rather than looked up here: an order being edited already knows its origin's code
   * and name, and re-fetching them to render a value that is already on screen would make the
   * field flash empty every time the drawer opens. */
  selected: LookupOption | null
  onChange: (option: LookupOption | null) => void
  /** Runs the search. Given the trimmed term and an abort signal; must return at most a page. */
  search: (term: string, signal: AbortSignal) => Promise<LookupOption[]>
  /** Distinguishes this field's cached searches from every other lookup's. */
  queryKey: readonly unknown[]
  placeholder: string
  disabled?: boolean
  /** Draws the error treatment; the message itself belongs to `FormField`. */
  invalid?: boolean
  'aria-describedby'?: string
}

/** How long typing has to stop before a request goes out. Long enough that typing a six-letter
 * code is one query rather than six, short enough not to feel like the field is thinking. */
const DEBOUNCE_MS = 250

/**
 * An async autocomplete over a company's master data.
 *
 * A plain `<select>` stops working the moment a tenant has more locations than fit in one
 * fetch: the Orders drawer used to load 200 origins and 200 destinations and silently offered
 * no way to reach the 201st. This asks the server instead, one page at a time, filtered by
 * what the operator typed - which is also the only version that scales to the 10,000
 * orders/day target the architecture is written against.
 *
 * The control is an editable combobox (WAI-ARIA 1.2): a text input owning a listbox, with
 * `aria-activedescendant` moving the highlight while focus stays in the input, so typing and
 * navigating never fight over the caret.
 *
 * Selection is by id and the id is the only thing that leaves this component. A typed term
 * that matches nothing selects nothing - there is no "closest match" guess, because a shipment
 * sent to the wrong store is not a mistake worth being clever about.
 */
export function LookupField({
  id,
  value,
  selected,
  onChange,
  search,
  queryKey,
  placeholder,
  disabled = false,
  invalid = false,
  'aria-describedby': describedBy,
}: LookupFieldProps) {
  const { t } = useTranslation('common')
  const listId = useId()
  const containerRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const [open, setOpen] = useState(false)
  const [term, setTerm] = useState('')
  const [debounced, setDebounced] = useState('')
  const [highlightIndex, setHighlight] = useState(0)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term.trim()), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [term])

  // Only while the panel is open: a closed lookup has no list to fill, and an order drawer with
  // two of these would otherwise fire two searches on mount that nobody asked for.
  const optionsQuery = useQuery({
    queryKey: [...queryKey, debounced],
    queryFn: ({ signal }) => search(debounced, signal),
    enabled: open && !disabled,
    // The list keeps its previous contents while the next term loads, so the panel does not
    // collapse to an empty box between keystrokes.
    placeholderData: keepPreviousData,
  })
  const options = optionsQuery.data ?? []

  // Clamped during render rather than reset from an effect on the results arriving: a shorter
  // list must not leave the highlight pointing past its end, and an effect that corrects it
  // would render the broken state first and then re-render.
  const highlight = options.length === 0 ? 0 : Math.min(highlightIndex, options.length - 1)

  useEffect(() => {
    if (!open) return
    function onPointerDown(event: MouseEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open])

  function choose(option: LookupOption) {
    onChange(option)
    setOpen(false)
    setTerm('')
    inputRef.current?.focus()
  }

  function clear() {
    onChange(null)
    setTerm('')
    setOpen(false)
    inputRef.current?.focus()
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (!open) {
        setOpen(true)
        return
      }
      if (options.length === 0) return
      // Stepped from the clamped `highlight`, not from the raw state: the two differ exactly
      // when a shorter list has just arrived, and that is the case where moving from a stale
      // out-of-range index would jump somewhere the operator never was.
      const step = event.key === 'ArrowDown' ? 1 : -1
      setHighlight((highlight + step + options.length) % options.length)
      return
    }
    if (event.key === 'Enter') {
      // Only when the panel is open, so Enter in a closed lookup submits the form the way it
      // does from any other field.
      if (!open) return
      event.preventDefault()
      const option = options[highlight]
      if (option) choose(option)
      return
    }
    if (event.key === 'Escape' && open) {
      event.preventDefault()
      setOpen(false)
      return
    }
    if (event.key === 'Tab') {
      setOpen(false)
    }
  }

  // While the panel is closed the input shows the selection; while it is open it shows what is
  // being typed. Two states, one field - the alternative is a read-only field plus a separate
  // search box, which is two controls for one decision.
  const display = open ? term : selected === null ? '' : `${selected.code} — ${selected.name}`

  return (
    <div className="tms-lookup" ref={containerRef}>
      <div className="tms-lookup-control">
        <input
          id={id}
          ref={inputRef}
          type="text"
          role="combobox"
          autoComplete="off"
          className={`form-control${invalid ? ' is-invalid' : ''}`}
          value={display}
          placeholder={placeholder}
          disabled={disabled}
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-describedby={describedBy}
          aria-activedescendant={open && options[highlight] ? `${listId}-${highlight}` : undefined}
          onChange={(event) => {
            setTerm(event.target.value)
            // A new term means a new list; the highlight belongs at its top, and the keystroke
            // is the event that caused that - not the arrival of the results.
            setHighlight(0)
            setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
        />
        {value !== '' && !disabled && (
          <button
            type="button"
            className="tms-lookup-clear"
            aria-label={t('actions.clear')}
            onClick={clear}
          >
            <i className="bi bi-x-lg" aria-hidden="true" />
          </button>
        )}
      </div>

      {open && (
        <div className="tms-menu tms-lookup-panel" role="presentation">
          <ul className="list-unstyled mb-0" id={listId} role="listbox" aria-label={placeholder}>
            {options.map((option, index) => (
              <li key={option.id} role="none">
                <button
                  type="button"
                  id={`${listId}-${index}`}
                  role="option"
                  aria-selected={option.id === value}
                  className={`tms-menu-item tms-menu-item-stacked${
                    index === highlight ? ' is-selected' : ''
                  }`}
                  // `mousedown` rather than `click`: the input's blur would otherwise close the
                  // panel out from under the pointer before the click ever landed.
                  onMouseDown={(event) => {
                    event.preventDefault()
                    choose(option)
                  }}
                  onMouseEnter={() => setHighlight(index)}
                >
                  <span className="tms-menu-item-title tms-truncate">{option.name}</span>
                  <span className="tms-menu-item-meta tms-code">{option.code}</span>
                </button>
              </li>
            ))}
          </ul>
          {options.length === 0 && (
            <p className="tms-lookup-empty mb-0" role="status">
              {optionsQuery.isFetching ? t('loading.generic') : t('empty.noMatches')}
            </p>
          )}
        </div>
      )}
    </div>
  )
}
