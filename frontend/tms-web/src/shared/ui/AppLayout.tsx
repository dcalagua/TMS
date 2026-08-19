import { Outlet } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'

/** Application shell: top bar, side navigation and the routed content area. Rendered only for
 * signed-in users - `ProtectedRoute` guards the routes that mount it. */
export function AppLayout() {
  return (
    <div className="d-flex flex-column min-vh-100">
      <TopBar />

      <div className="d-flex flex-grow-1">
        <Sidebar />

        <main className="flex-grow-1 bg-body-tertiary min-w-0">
          <div className="container-fluid py-3">
            <Outlet />
          </div>
        </main>
      </div>

      <footer className="border-top py-2">
        <div className="container-fluid small text-body-secondary d-flex justify-content-between">
          <span>TMS by EBIM</span>
          <span>Transport Management System</span>
        </div>
      </footer>
    </div>
  )
}
