import { useId, useLayoutEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { useMenu } from './useMenu'

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
}

export interface SelectProps {
  /** Put the same id on the field's `<label htmlFor>`. A button is a labelable element, so the
   * existing FilterField/FormField wiring keeps working unchanged. */
  id?: string
  value: string
  onChange: (value: string) => void
  options: SelectOption[]
  /** Shown when `value` matches no option - the "all types" style entry, if there is one, is
   * an ordinary option with an empty value rather than this. */
  placeholder?: string
  disabled?: boolean
  /** Draws the error treatment. The message itself belongs to FormField. */
  invalid?: boolean
  /** `sm` matches the filter bar's compact controls; `md` is the form default. */
  size?: 'sm' | 'md'
  name?: string
  'aria-describedby'?: string
}

/**
 * The product's select.
 *
 * A native `<select>` is the right control in almost every way - it is accessible, it works on
 * touch, it needs no JavaScript - with one exception that matters here: the option list is
 * drawn by the operating system, so no stylesheet can reach it. On Windows that means a
 * saturated blue highlight inside an interface that has none anywhere else, and it is the one
 * part of a themed application that ignores the theme entirely.
 *
 * So the list is ours. Everything else is deliberately kept close to the native contract:
 * a `combobox` trigger with a `listbox` panel, arrow keys, Home/End, Enter and Escape, all of
 * which come from `useMenu` - the same behaviour the row action menu and the account menu use,
 * rather than a second implementation that drifts from them.
 *
 * A hidden input carries the value so the control still posts inside a plain form and so
 * form libraries that read the DOM find what they expect.
 */
export function Select({
  id,
  value,
  onChange,
  options,
  placeholder,
  disabled = false,
  invalid = false,
  size = 'md',
  name,
  'aria-describedby': describedBy,
}: SelectProps) {
  const listId = useId()
  const { open, toggle, close, containerRef, triggerRef, menuRef, menuStyle, registerItem, onKeyDown } = useMenu(
    options.length,
    {
      // `useMenu` right-aligns by default, which is what a row action menu wants. A select
      // drops from its own field, so it aligns to the field's left edge instead - otherwise
      // the panel hangs off to the side of the control it belongs to.
      align: 'start',
      isEnabled: (index) => !options[index]?.disabled,
    },
  )

  /* The panel matches the field's width instead of sizing to its longest option: a list
     narrower than the control it drops from looks detached from it, and one wider than the
     column pushes into whatever sits beside it. Measured on open, because the field itself is
     laid out by the filter bar or the form grid and its width is not known up front. */
  const [panelWidth, setPanelWidth] = useState<number>()
  useLayoutEffect(() => {
    if (open) {
      setPanelWidth(triggerRef.current?.offsetWidth)
    }
  }, [open, triggerRef])

  const selected = useMemo(() => options.find((option) => option.value === value), [options, value])
  const selectedIndex = selected ? options.indexOf(selected) : -1

  return (
    <div className="tms-select" ref={containerRef}>
      <button
        ref={triggerRef}
        id={id}
        type="button"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-describedby={describedBy}
        aria-invalid={invalid || undefined}
        disabled={disabled}
        className={`tms-select-trigger tms-select-${size}${open ? ' is-open' : ''}${invalid ? ' is-invalid' : ''}`}
        onClick={toggle}
        onKeyDown={(event) => {
          // Opening with the keyboard is part of the combobox contract; once open, useMenu owns
          // the arrows so the behaviour matches every other panel in the product.
          if (!open && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
            event.preventDefault()
            toggle()
            return
          }
          onKeyDown(event)
        }}
      >
        <span className={`tms-select-value${selected ? '' : ' is-placeholder'} tms-truncate`}>
          {selected?.label ?? placeholder ?? ''}
        </span>
        <i className="bi bi-chevron-down tms-select-caret" aria-hidden="true" />
      </button>

      {name && <input type="hidden" name={name} value={value} />}

      {open &&
        createPortal(
          <div
            id={listId}
            ref={menuRef}
            role="listbox"
            tabIndex={-1}
            aria-activedescendant={selectedIndex >= 0 ? `${listId}-${selectedIndex}` : undefined}
            className="tms-select-list"
            style={{ ...menuStyle, width: panelWidth }}
            onKeyDown={onKeyDown}
          >
            {options.map((option, index) => {
              const isSelected = option.value === value
              return (
                <button
                  key={option.value || `__empty-${index}`}
                  id={`${listId}-${index}`}
                  ref={registerItem(index)}
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  disabled={option.disabled}
                  className={`tms-select-option${isSelected ? ' is-selected' : ''}`}
                  onClick={() => {
                    onChange(option.value)
                    close(true)
                  }}
                >
                  <span className="tms-select-option-label">{option.label}</span>
                  {isSelected && <i className="bi bi-check2 tms-select-check" aria-hidden="true" />}
                </button>
              )
            })}
          </div>,
          document.body,
        )}
    </div>
  )
}
