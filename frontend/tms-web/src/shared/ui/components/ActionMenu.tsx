import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { useMenu } from './useMenu'

export interface ActionMenuItem {
  key: string
  label: string
  /** Bootstrap Icons class, for example `bi-pencil`. */
  icon?: string
  onSelect: () => void
  dangerous?: boolean
  disabled?: boolean
}

export interface ActionMenuProps {
  items: ActionMenuItem[]
  /** Overrides the trigger's accessible name; defaults to a generic "open actions menu". */
  label?: string
}

/**
 * The `...` menu a table row uses for its secondary actions, so a list of twenty rows is not a
 * wall of forty buttons. Keyboard behaviour comes from {@link useMenu}.
 */
export function ActionMenu({ items, label }: ActionMenuProps) {
  const { t } = useTranslation('common')
  const menuId = useId()
  const { open, toggle, close, containerRef, triggerRef, registerItem, onKeyDown } = useMenu(items.length, (index) => items[index]?.disabled !== true)

  if (items.length === 0) {
    return null
  }

  const triggerLabel = label ?? t('actions.openMenu')

  return (
    <div className="position-relative d-inline-block" ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        className="tms-icon-btn"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={triggerLabel}
        title={triggerLabel}
        onClick={toggle}
      >
        <i className="bi bi-three-dots-vertical" aria-hidden="true" />
      </button>

      {open && (
        <div
          id={menuId}
          role="menu"
          tabIndex={-1}
          className="dropdown-menu show shadow-sm"
          style={{ position: 'absolute', right: 0, top: '100%', minWidth: '11rem', zIndex: 1000 }}
          onKeyDown={onKeyDown}
        >
          {items.map((item, index) => (
            <button
              key={item.key}
              ref={registerItem(index)}
              type="button"
              role="menuitem"
              disabled={item.disabled}
              className={`dropdown-item d-flex align-items-center gap-2${item.dangerous ? ' text-danger' : ''}`}
              onClick={() => {
                close(false)
                item.onSelect()
              }}
            >
              {item.icon && <i className={`bi ${item.icon}`} aria-hidden="true" />}
              <span>{item.label}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
