import type { ButtonHTMLAttributes } from 'react'

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  /** Bootstrap Icons class, for example `bi-pencil`. */
  icon: string
  /** Required: an icon-only control has no accessible name without it. */
  label: string
  variant?: 'default' | 'inverse'
}

/** Icon-only control. `label` is mandatory precisely because the icon carries no text, and it
 * doubles as the hover tooltip. */
export function IconButton({ icon, label, variant = 'default', className, ...rest }: IconButtonProps) {
  const variantClass = variant === 'inverse' ? ' tms-icon-btn-inverse' : ''

  return (
    <button
      type="button"
      className={`tms-icon-btn${variantClass}${className ? ` ${className}` : ''}`}
      aria-label={label}
      title={label}
      {...rest}
    >
      <i className={`bi ${icon}`} aria-hidden="true" />
    </button>
  )
}
