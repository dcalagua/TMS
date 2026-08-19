import { useId } from 'react'
import { createPortal } from 'react-dom'
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
 * wall of forty buttons. Keyboard behaviour and placement come from {@link useMenu}.
 *
 * The panel is portalled to the body rather than nested in the cell. A table row lives inside
 * `.tms-table-wrap` (`overflow: hidden`) and `.tms-table-scroll` (`overflow-x: auto`), so an
 * absolutely positioned child is clipped by the first and inflates the scrollable area of the
 * second - the last row's menu would open behind the panel edge and add a scrollbar. Raising
 * z-index cannot fix that; an overflow container clips whatever the stacking order says. The
 * only structural answer is to leave the table's flow, which is what this does.
 *
 * The panel is styled by the design system rather than by `.dropdown-menu`, which carries
 * Bootstrap's blue active state and a set of paddings that do not match the rest of the app.
 */
export function ActionMenu({ items, label }: ActionMenuProps) {
  const { t } = useTranslation('common')
  const menuId = useId()
  const { open, toggle, close, containerRef, triggerRef, menuRef, menuStyle, registerItem, onKeyDown } = useMenu(
    items.length,
    { align: 'end', isEnabled: (index) => items[index]?.disabled !== true },
  )

  if (items.length === 0) {
    return null
  }

  const triggerLabel = label ?? t('actions.openMenu')

  return (
    <div className="d-inline-block" ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        className={`tms-icon-btn${open ? ' is-active' : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={triggerLabel}
        title={triggerLabel}
        onClick={toggle}
      >
        <i className="bi bi-three-dots-vertical" aria-hidden="true" />
      </button>

      {open &&
        createPortal(
          <div
            id={menuId}
            ref={menuRef}
            role="menu"
            tabIndex={-1}
            className="tms-menu"
            style={menuStyle}
            onKeyDown={onKeyDown}
          >
            {items.map((item, index) => (
              <button
                key={item.key}
                ref={registerItem(index)}
                type="button"
                role="menuitem"
                disabled={item.disabled}
                className={`tms-menu-item${item.dangerous ? ' is-dangerous' : ''}`}
                onClick={() => {
                  close(false)
                  item.onSelect()
                }}
              >
                {item.icon && <i className={`bi ${item.icon}`} aria-hidden="true" />}
                <span>{item.label}</span>
              </button>
            ))}
          </div>,
          document.body,
        )}
    </div>
  )
}
