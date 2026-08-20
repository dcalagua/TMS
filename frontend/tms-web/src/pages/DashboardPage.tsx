import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { fetchOrders } from '../shared/api/ordersApi'
import { fetchSystemInfo } from '../shared/api/systemApi'
import { fetchVehicles } from '../shared/api/vehiclesApi'
import { useAuth } from '../shared/auth/AuthContext'
import { useCompany } from '../shared/company/CompanyContext'
import { NAV_GROUPS } from '../shared/ui/navConfig'
import { useFormat } from '../shared/i18n/format'
import { AppCard, KpiCard, PageHeader, StatusBadge } from '../shared/ui/components'

/**
 * Landing screen: who you are, which company you are operating in, and the modules your
 * membership actually grants in it.
 *
 * The counters are real. There is still no KPI endpoint, so each one is the `totalElements` of
 * a list query the operator could run themselves - asked for with `size: 1`, because the page
 * of rows is thrown away and only the server's count is kept. That keeps the dashboard honest:
 * every figure here is reachable by clicking it, and none of it is computed in the browser from
 * a partial page.
 *
 * They are ordered by what an operator can act on. "To plan" comes first because it is the
 * queue that stalls the day if nobody works it; master-data counts come last because they
 * change monthly, not hourly.
 */
export function DashboardPage() {
  const { t } = useTranslation(['dashboard', 'navigation', 'common'])
  const { user } = useAuth()
  const { selected, hasCapability, status: companyStatus } = useCompany()

  const backend = useQuery({
    queryKey: ['system', 'info'],
    queryFn: ({ signal }) => fetchSystemInfo(signal),
    retry: false,
  })

  const format = useFormat()
  const companyId = selected?.id ?? ''
  const canSeeOrders = hasCapability('ORDERS_VIEW')
  const canSeeFleet = hasCapability('FLEET_VIEW')

  /** One count, asked for as cheaply as the API allows. */
  function useCount(key: string, enabled: boolean, run: (signal: AbortSignal) => Promise<{ totalElements: number }>) {
    return useQuery({
      queryKey: ['kpi', key, companyId],
      queryFn: ({ signal }) => run(signal),
      enabled: Boolean(companyId) && enabled && companyStatus === 'ready',
      select: (page) => page.totalElements,
      staleTime: 30_000,
    })
  }

  const ordersReady = useCount('orders-ready', canSeeOrders, (signal) =>
    fetchOrders({ companyId, size: 1, status: 'READY_FOR_PLANNING', signal }),
  )
  const ordersNotReady = useCount('orders-not-ready', canSeeOrders, (signal) =>
    fetchOrders({ companyId, size: 1, status: 'NOT_READY', signal }),
  )
  const ordersPlanned = useCount('orders-planned', canSeeOrders, (signal) =>
    fetchOrders({ companyId, size: 1, status: 'PLANNED', signal }),
  )
  const activeVehicles = useCount('vehicles-active', canSeeFleet, (signal) =>
    fetchVehicles({ companyId, size: 1, active: true, signal }),
  )

  const count = (query: { data?: number }) => (query.data === undefined ? undefined : format.quantity(query.data))

  // Quick access mirrors the sidebar's capability gating: it can only offer what `/me` granted.
  const availableGroups = NAV_GROUPS.filter(
    (group) => !group.capability || companyStatus !== 'ready' || hasCapability(group.capability),
  )

  const apiBadge = backend.isError ? (
    <StatusBadge label={t('apiUnreachable')} tone="danger" />
  ) : backend.isSuccess ? (
    <StatusBadge label={t('apiReachable')} tone="success" />
  ) : (
    <StatusBadge label={t('apiChecking')} tone="neutral" />
  )

  return (
    <>
      <PageHeader
        icon="speedometer2"
        title={t('welcome', { name: user?.email ?? '' })}
        description={selected ? t('subtitle', { company: selected.name }) : t('noCompany')}
        actions={apiBadge}
      />

      {backend.isError && (
        <div className="alert alert-warning d-flex align-items-start gap-2 py-2 small" role="alert">
          <i className="bi bi-exclamation-triangle-fill mt-1" aria-hidden="true" />
          <span>{t('apiUnreachableHint')}</span>
        </div>
      )}

      {/* Ordered by urgency, not by module. The queue that stalls the day is first, and it is
          the only one that takes a tone - colouring all four would say nothing about which one
          needs attention. */}
      <div className="tms-kpi-grid">
        {canSeeOrders && (
          <>
            <KpiCard
              icon="bi-inbox"
              tone={ordersReady.data && ordersReady.data > 0 ? 'warning' : 'neutral'}
              label={t('kpi.ordersReady')}
              hint={t('kpi.ordersReadyHint')}
              value={count(ordersReady)}
              isLoading={ordersReady.isPending}
              to="/orders"
            />
            <KpiCard
              icon="bi-hourglass-split"
              label={t('kpi.ordersNotReady')}
              hint={t('kpi.ordersNotReadyHint')}
              value={count(ordersNotReady)}
              isLoading={ordersNotReady.isPending}
              to="/orders"
            />
            <KpiCard
              icon="bi-check2-circle"
              tone="success"
              label={t('kpi.ordersPlanned')}
              hint={t('kpi.ordersPlannedHint')}
              value={count(ordersPlanned)}
              isLoading={ordersPlanned.isPending}
              to="/orders"
            />
          </>
        )}
        {canSeeFleet && (
          <KpiCard
            icon="bi-truck-front"
            tone="info"
            label={t('kpi.fleet')}
            hint={t('kpi.fleetHint')}
            value={count(activeVehicles)}
            isLoading={activeVehicles.isPending}
            to="/fleet/vehicles"
          />
        )}
      </div>

      <AppCard title={t('quickAccess')}>
        {availableGroups.length === 0 ? (
          <p className="text-body-secondary small mb-0">{t('quickAccessEmpty')}</p>
        ) : (
          <div className="tms-modules">
            {availableGroups.map((group) => (
              <div key={group.labelKey} className="tms-modules-group">
                <p className="tms-modules-group-label">{t(group.labelKey, { ns: 'navigation' })}</p>
                <div className="tms-modules-grid">
                  {group.items.map((item) => (
                    <Link key={item.to} to={item.to} className="tms-module">
                      {/* The tile carries the module's own accent, so the eye finds "Vehicles"
                          by its colour before it has read the word. */}
                      <span className={`tms-module-tile tms-accent-${group.accent}`} aria-hidden="true">
                        <i className={`bi ${item.icon}`} />
                      </span>
                      <span className="tms-module-label">{t(item.labelKey, { ns: 'navigation' })}</span>
                    </Link>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </AppCard>

      {/* The session facts are context, not content: one quiet strip under the work rather
          than a panel competing with it. The account menu carries the same facts for anyone
          who goes looking. */}
      <dl className="tms-session-strip">
        <div>
          <dt>{t('email')}</dt>
          <dd className="tms-truncate">{user?.email ?? '-'}</dd>
        </div>
        <div>
          <dt>{t('company')}</dt>
          <dd className="tms-truncate">{selected?.name ?? '-'}</dd>
        </div>
        <div>
          <dt>{t('organization')}</dt>
          <dd className="tms-truncate">{selected?.organization.name ?? '-'}</dd>
        </div>
        <div>
          <dt>{t('timeZone')}</dt>
          <dd className="tms-truncate">{selected?.timeZone ?? '-'}</dd>
        </div>
      </dl>

    </>
  )
}
