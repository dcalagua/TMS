import { useTranslation } from 'react-i18next'
import { CompanySelector } from './CompanySelector'
import { LanguageSwitcher } from './LanguageSwitcher'
import { SIDEBAR_ID } from './Sidebar'
import { UserMenu } from './UserMenu'

export interface TopBarProps {
  /** Reflects the mobile drawer state so the toggle can expose `aria-expanded` honestly. */
  navOpen: boolean
  onToggleNav: () => void
}

/** Fixed top bar: brand, mobile sidebar toggle, company selector, language switch, user menu. */
export function TopBar({ navOpen, onToggleNav }: TopBarProps) {
  const { t } = useTranslation('navigation')

  return (
    <nav className="navbar navbar-dark bg-dark border-bottom border-secondary-subtle">
      <div className="container-fluid gap-2">
        <button
          type="button"
          className="btn btn-outline-light d-lg-none"
          onClick={onToggleNav}
          aria-controls={SIDEBAR_ID}
          aria-expanded={navOpen}
          aria-label={t('toggle')}
        >
          <span className="navbar-toggler-icon" />
        </button>

        <span className="navbar-brand fw-semibold mb-0 me-auto me-lg-3">
          TMS <span className="text-secondary fw-normal">by EBIM</span>
        </span>

        <div className="d-flex align-items-center gap-2">
          <CompanySelector />
          <LanguageSwitcher />
          <UserMenu />
        </div>
      </div>
    </nav>
  )
}
