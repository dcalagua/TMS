import { useQuery } from '@tanstack/react-query'
import { fetchSystemInfo } from '../shared/api/systemApi'

/**
 * Placeholder landing screen. Its only real job today is to prove the
 * React -> Spring Boot path works end to end before business screens exist.
 */
export function DashboardPage() {
  const backend = useQuery({
    queryKey: ['system', 'info'],
    queryFn: ({ signal }) => fetchSystemInfo(signal),
    retry: false,
  })

  return (
    <>
      <div className="d-flex align-items-center justify-content-between mb-3">
        <h1 className="h4 mb-0">Dashboard</h1>
        <span className="badge text-bg-secondary">V1 foundation</span>
      </div>

      <div className="row g-3">
        <div className="col-12 col-lg-5">
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

        <div className="col-12 col-lg-7">
          <div className="card shadow-sm h-100">
            <div className="card-header py-2 fw-semibold">Next steps</div>
            <div className="card-body">
              <p className="text-body-secondary small">
                Business modules are delivered as vertical slices. Master data, fleet, orders and
                manual planning screens arrive in later steps.
              </p>
              <ul className="small mb-0">
                <li>Database and tenancy foundation</li>
                <li>Supabase JWT authentication and company scoping</li>
                <li>Master data, fleet, orders</li>
                <li>Manual planning</li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </>
  )
}
