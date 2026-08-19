import { useTranslation } from 'react-i18next'
import { CompanySelector } from './CompanySelector'
import { LanguageSwitcher } from './LanguageSwitcher'
import { SIDEBAR_ID } from './Sidebar'
import { UserMenu } from './UserMenu'
import { IconButton } from './components/IconButton'

export interface TopBarProps {
  /** Reflects the mobile drawer state so the toggle can expose `aria-expanded` honestly. */
  navOpen: boolean
  onToggleNav: () => void
  collapsed: boolean
  onToggleCollapsed: () => void
}

/** Top bar: brand, navigation toggles, company scope, language and the user menu. */
export function TopBar({ navOpen, onToggleNav, collapsed, onToggleCollapsed }: TopBarProps) {
  const { t } = useTranslation(['navigation', 'common'])

  return (
    <header className="tms-topbar d-flex align-items-center gap-2 px-3">
      <IconButton
        icon="bi-list"
        label={t('toggle')}
        className="d-lg-none"
        onClick={onToggleNav}
        aria-controls={SIDEBAR_ID}
        aria-expanded={navOpen}
      />

      <IconButton
        icon={collapsed ? 'bi-chevron-double-right' : 'bi-chevron-double-left'}
        label={collapsed ? t('expand') : t('collapse')}
        className="d-none d-lg-inline-flex"
        onClick={onToggleCollapsed}
        aria-controls={SIDEBAR_ID}
        aria-expanded={!collapsed}
      />

      <span className="tms-brand d-flex align-items-center gap-2 me-auto flex-shrink-0">
        <span className="tms-brand-mark" aria-hidden="true">
          TMS
        </span>
        <span className="d-none d-sm-inline">
          TMS <span className="tms-brand-accent">by EBIM</span>
        </span>
      </span>

      {/* `tms-min-w-0` is what lets the company name actually truncate: without it this flex
          item sizes to its content and pushes the language switch off a narrow phone. */}
      <div className="d-flex align-items-center gap-2 tms-min-w-0">
        <CompanySelector />
        <LanguageSwitcher />
        <UserMenu />
      </div>
    </header>
  )
}
