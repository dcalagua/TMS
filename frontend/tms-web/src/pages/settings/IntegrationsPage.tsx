import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useCompany } from '../../shared/company/CompanyContext'
import { EmptyState, PageHeader } from '../../shared/ui/components'
import { InboundPanel } from './InboundPanel'
import { OutboundPanel } from './OutboundPanel'

type HubTab = 'inbound' | 'outbound'

/**
 * The Integration Hub: everything connected to this company, in one screen.
 *
 * Two tabs, and the split is by direction rather than by object, because that is how the question
 * is actually asked. **Inbound** is who may write into us and what they sent; **outbound** is where
 * our events go and whether they arrived. The two are separate permissions server-side for the same
 * reason - a credential is a way in and a subscription is a way out, and mismanaging them fails in
 * opposite directions.
 *
 * A caller holding neither permission sees an empty state rather than a broken screen: the route is
 * reachable from the menu only with one of them, but a direct URL must not answer with a wall of
 * 403s.
 */
export function IntegrationsPage() {
  const { t } = useTranslation('settings')
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''

  const canReadClients = hasPermission('integration.client:read')
  const canReadWebhooks = hasPermission('integration.webhook:read')
  const [tab, setTab] = useState<HubTab>(canReadClients ? 'inbound' : 'outbound')

  return (
    <div>
      <PageHeader
        icon="plug"
        title={t('integrations.title')}
        description={t('integrations.description')}
      />

      {!canReadClients && !canReadWebhooks ? (
        <EmptyState
          icon="bi-shield-lock"
          title={t('integrations.noAccess.title')}
          message={t('integrations.noAccess.message')}
        />
      ) : (
        <>
          <ul className="nav nav-tabs mb-3" role="tablist">
            {canReadClients && (
              <li className="nav-item" role="presentation">
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'inbound'}
                  className={`nav-link${tab === 'inbound' ? ' active' : ''}`}
                  onClick={() => setTab('inbound')}
                >
                  <i className="bi bi-box-arrow-in-down me-2" aria-hidden="true" />
                  {t('integrations.tabs.inbound')}
                </button>
              </li>
            )}
            {canReadWebhooks && (
              <li className="nav-item" role="presentation">
                <button
                  type="button"
                  role="tab"
                  aria-selected={tab === 'outbound'}
                  className={`nav-link${tab === 'outbound' ? ' active' : ''}`}
                  onClick={() => setTab('outbound')}
                >
                  <i className="bi bi-box-arrow-up-right me-2" aria-hidden="true" />
                  {t('integrations.tabs.outbound')}
                </button>
              </li>
            )}
          </ul>

          {tab === 'inbound' && canReadClients && <InboundPanel companyId={companyId} />}
          {tab === 'outbound' && canReadWebhooks && <OutboundPanel companyId={companyId} />}
        </>
      )}
    </div>
  )
}
