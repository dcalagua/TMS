import { useTranslation } from 'react-i18next'
import { Breadcrumb } from './Breadcrumb'
import { CompanySelector } from './CompanySelector'
import { NavSearch } from './NavSearch'
import { NotificationsMenu } from './NotificationsMenu'
import { ModeToggle } from './ThemeControls'
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

/**
 * Top bar: two intentional halves rather than a row of controls pushed to one end.
 *
 * On the left the two things an operator acts on - the screen search and where they currently
 * are; on the right, who they are and what scope they are working in. The product name is
 * deliberately absent: the sidebar carries it, and repeating it here spent the only part of the
 * bar with room to say something the user does not already know.
 *
 * Search leads the left-hand group because it is a control, not a label. Putting it in the
 * centre gave the bar a symmetric look and made the one interactive element the hardest to
 * find.
 */
export function TopBar({ navOpen, onToggleNav, collapsed, onToggleCollapsed }: TopBarProps) {
  const { t } = useTranslation(['navigation', 'common'])

  return (
    <header className="tms-topbar">
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

      {/* Title first, search centred, account last. The search is given the middle
          by auto margins rather than by sitting between two fixed groups, so a
          long screen title cannot shove it off centre. */}
      <Breadcrumb />

      <NavSearch />

      {/* `tms-min-w-0` is what lets the company name actually truncate: without it this flex
          item sizes to its content and pushes the language switch off a narrow phone. */}
      <div className="tms-topbar-actions tms-min-w-0">
        {/* From `lg` up the sidebar carries the company control, so showing it here too would
            be the same switcher twice. Below `lg` the sidebar is a closed drawer, and the
            active company must stay visible without opening it. */}
        <CompanySelector className="d-lg-none" />
        <ModeToggle />
        <NotificationsMenu />
        {/* The rule separates the two icon actions from the identity chip, which is a
            different kind of control: one opens a panel about you, the others act on the
            page. */}
        <span className="tms-topbar-divider d-none d-sm-block" aria-hidden="true" />
        <UserMenu />
      </div>
    </header>
  )
}
