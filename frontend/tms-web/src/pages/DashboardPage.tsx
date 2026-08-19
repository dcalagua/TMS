import { useQuery } from '@tanstack/react-query'
import { fetchSystemInfo } from '../shared/api/systemApi'
import { useAuth } from '../shared/auth/AuthContext'
import { useCompany } from '../shared/company/CompanyContext'

/**
 * Landing screen. Proves the React -> Spring Boot path works end to end and shows the
 * identity/company context resolved from Supabase Auth + `GET /api/v1/me`.
 */
export function DashboardPage() {
  const backend = useQuery({
    queryKey: ['system', 'info'],
    queryFn: ({ signal }) => fetchSystemInfo(signal),
    retry: false,
  })
  const { user } = useAuth()
  const { selected } = useCompany()

  return (
    <>
      <div className="d-flex align-items-center justify-content-between mb-3">
        <h1 className="h4 mb-0">Dashboard</h1>
        <span className="badge text-bg-secondary">V1 foundation</span>
      </div>

      <div className="row g-3">
        <div className="col-12 col-lg-4">
          <div className="card shadow-sm h-100">
            <div className="card-header py-2 fw-semibold">Signed in as</div>
            <div className="card-body">
              <dl className="row mb-0 small">
                <dt className="col-5 text-body-secondary">Email</dt>
                <dd className="col-7">{user?.email ?? '-'}</dd>
                <dt className="col-5 text-body-secondary">Company</dt>
                <dd className="col-7 mb-0">{selected?.name ?? '-'}</dd>
              </dl>
            </div>
          </div>
        </div>

        <div className="col-12 col-lg-4">
          <div className="card shadow-sm h-100">
            <div className="card-header py-2 fw-semibold">Backend connection</div>
            <div className="card-body">
              {backend.isPending && (
                <p className="mb-0 text-body-secondary" role="status">
                  Checking the TMS API...
                </p>
              )}

              {backend.isError && (
                <div className="alert alert-warning mb-0" role="alert">
                  <div className="fw-semibold">TMS API unreachable</div>
                  <div className="small">Start the backend, then reload this page.</div>
                </div>
              )}

              {backend.isSuccess && (
                <dl className="row mb-0 small">
                  <dt className="col-5 text-body-secondary">Service</dt>
                  <dd className="col-7">{backend.data.application}</dd>
                  <dt className="col-5 text-body-secondary">Version</dt>
                  <dd className="col-7">{backend.data.version}</dd>
                  <dt className="col-5 text-body-secondary">Status</dt>
                  <dd className="col-7">
                    <span className="badge text-bg-success">{backend.data.status}</span>
                  </dd>
                  <dt className="col-5 text-body-secondary">Profiles</dt>
                  <dd className="col-7 mb-0">{backend.data.profiles.join(', ') || '-'}</dd>
                </dl>
              )}
            </div>
          </div>
        </div>

        <div className="col-12 col-lg-4">
          <div className="card shadow-sm h-100">
            <div className="card-header py-2 fw-semibold">Next steps</div>
            <div className="card-body">
              <p className="text-body-secondary small">
                Business modules are delivered as vertical slices, from master data through manual
                planning.
              </p>
              <ul className="small mb-0">
                <li>Master data: origins, destinations, zones, frequencies, routes</li>
                <li>Fleet: carriers, vehicle types, vehicles</li>
                <li>Orders</li>
                <li>Manual planning and trips</li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </>
  )
}
