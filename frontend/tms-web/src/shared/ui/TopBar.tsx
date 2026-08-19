import { CompanySelector } from './CompanySelector'
import { SIDEBAR_ID } from './Sidebar'
import { UserMenu } from './UserMenu'

/** Fixed top bar: brand, mobile sidebar toggle, company selector, user menu. */
export function TopBar() {
  return (
    <nav className="navbar navbar-dark bg-dark border-bottom border-secondary-subtle">
      <div className="container-fluid gap-2">
        <button
          type="button"
          className="btn btn-outline-light d-lg-none"
          data-bs-toggle="offcanvas"
          data-bs-target={`#${SIDEBAR_ID}`}
          aria-controls={SIDEBAR_ID}
          aria-label="Toggle navigation"
        >
          <span className="navbar-toggler-icon" />
        </button>

        <span className="navbar-brand fw-semibold mb-0 me-auto me-lg-3">
          TMS <span className="text-secondary fw-normal">by EBIM</span>
        </span>

        <div className="d-flex align-items-center gap-2">
          <CompanySelector />
          <UserMenu />
        </div>
      </div>
    </nav>
  )
}
