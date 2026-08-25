import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  fetchAdministeredUsers,
  fetchAssignableRoles,
  restoreUserAccess,
  revokeUserAccess,
  type AdministeredUserView,
} from '../../shared/api/administrationApi'
import { describeApiError } from '../../shared/api/problemMessages'
import type { ApiError } from '../../shared/api/httpClient'
import { useCompany } from '../../shared/company/CompanyContext'
import {
  ActionMenu,
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  Select,
  StatusBadge,
  confirmDialog,
  type ActionMenuItem,
  type DataTableColumn,
} from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { UserFormDrawer } from './UserFormDrawer'

const PAGE_SIZE = 25

type AccessFilter = 'active' | 'revoked' | 'all'

interface AppliedFilters {
  search: string
  access: AccessFilter
}

const DEFAULT_FILTERS: AppliedFilters = { search: '', access: 'active' }

type ModalState = { mode: 'invite' } | { mode: 'edit'; user: AdministeredUserView } | null

/**
 * Who can act in this company.
 *
 * The row's identity is a membership, so the two states a row can be in are "has access" and
 * "access revoked" - not "user enabled" and "user disabled". That distinction is the whole reason
 * this screen is safe to give a company administrator: revoking here ends access to *this*
 * company and leaves the person's account, and their access to any other organization,
 * untouched.
 *
 * An organization-wide member is listed and cannot be edited. Hiding them would answer "who can
 * act here" with a list that is missing the people with the most authority.
 */
export function UsersPage() {
  const { t } = useTranslation('settings')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const { selected, profile, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManageAccess = hasPermission('iam.membership:manage')
  const canManageProfiles = hasPermission('iam.user:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const usersQuery = useQuery({
    queryKey: ['admin-users', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchAdministeredUsers({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: 'fullName,asc',
        search: filters.search || undefined,
        active: filters.access === 'all' ? undefined : filters.access === 'active',
        signal,
      }),
    placeholderData: keepPreviousData,
    enabled: companyId !== '',
  })

  // The catalogue is the same for every installation and changes only with a migration, so it is
  // fetched once and kept: re-reading it per drawer open would be a round trip for a constant.
  const rolesQuery = useQuery({
    queryKey: ['admin-roles', companyId],
    queryFn: ({ signal }) => fetchAssignableRoles(companyId, signal),
    enabled: companyId !== '',
    staleTime: Infinity,
  })

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['admin-users', companyId] })
  }

  async function toggleAccess(user: AdministeredUserView) {
    const confirmed = await confirmDialog({
      title: user.membershipActive
        ? t('users.revokeTitle', { name: user.fullName })
        : t('users.restoreTitle', { name: user.fullName }),
      text: user.membershipActive ? t('users.revokeText') : t('users.restoreText'),
      confirmLabel: user.membershipActive ? t('users.revoke') : t('users.restore'),
      dangerous: user.membershipActive,
    })
    if (!confirmed) return

    try {
      if (user.membershipActive) {
        await revokeUserAccess(companyId, user.membershipId)
        notifySuccess(t('users.revoked'), user.email)
      } else {
        await restoreUserAccess(companyId, user.membershipId)
        notifySuccess(t('users.restored'), user.email)
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  const columns: DataTableColumn<AdministeredUserView>[] = [
    {
      key: 'fullName',
      header: t('users.fullName'),
      render: (user) => (
        <span className="fw-semibold">
          {user.fullName}
          {user.appUserId === profile?.id && (
            <span className="text-body-secondary fw-normal"> ({t('users.you')})</span>
          )}
        </span>
      ),
    },
    { key: 'email', header: tc('fields.email'), render: (user) => user.email },
    {
      key: 'roles',
      header: t('users.roles'),
      render: (user) =>
        user.roleCodes.length === 0 ? '—' : (
          <span className="d-flex flex-wrap gap-1">
            {user.roleCodes.map((code) => (
              <StatusBadge key={code} label={code} tone="info" />
            ))}
          </span>
        ),
    },
    {
      key: 'scope',
      header: t('users.scope'),
      render: (user) =>
        user.organizationWide ? (
          <StatusBadge label={t('users.organizationWide')} tone="warning" />
        ) : (
          <StatusBadge label={t('users.companyScoped')} tone="neutral" />
        ),
    },
    {
      key: 'status',
      header: tc('columns.status'),
      render: (user) => {
        if (!user.userActive) {
          return <StatusBadge label={t('users.accountDisabled')} tone="danger" />
        }
        return user.membershipActive ? (
          <StatusBadge label={t('users.hasAccess')} tone="success" />
        ) : (
          <StatusBadge label={t('users.accessRevoked')} tone="neutral" />
        )
      },
    },
  ]

  if (canManageAccess || canManageProfiles) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (user) => {
        // An organization-wide membership reaches every company of the organization, so a company
        // screen offers nothing for it. The endpoints refuse it too; this only avoids offering a
        // button that would answer 409.
        if (user.organizationWide) {
          return <span className="text-body-secondary small">{t('users.notEditableHere')}</span>
        }
        const items: ActionMenuItem[] = []
        if (canManageProfiles || canManageAccess) {
          items.push({
            key: 'edit',
            label: tc('actions.edit'),
            icon: 'bi-pencil',
            onSelect: () => setModal({ mode: 'edit', user }),
          })
        }
        if (canManageAccess && user.appUserId !== profile?.id) {
          items.push({
            key: 'access',
            label: user.membershipActive ? t('users.revoke') : t('users.restore'),
            icon: user.membershipActive ? 'bi-person-dash' : 'bi-person-check',
            dangerous: user.membershipActive,
            onSelect: () => void toggleAccess(user),
          })
        }
        return <ActionMenu items={items} />
      },
    })
  }

  const pageData = usersQuery.data

  return (
    <div>
      <PageHeader
        icon="people"
        title={t('users.title')}
        description={t('users.description')}
        actions={
          canManageAccess && (
            <button
              type="button"
              className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2"
              onClick={() => setModal({ mode: 'invite' })}
            >
              <i className="bi bi-person-plus" aria-hidden="true" />
              {t('users.invite')}
            </button>
          )
        }
      />

      <FilterBar
        onSubmit={() => {
          setFilters(draftFilters)
          setPage(0)
        }}
        onReset={() => {
          setDraftFilters(DEFAULT_FILTERS)
          setFilters(DEFAULT_FILTERS)
          setPage(0)
        }}
      >
        <div>
          <label htmlFor="user-filter-search" className="form-label small mb-1">
            {t('users.searchLabel')}
          </label>
          <input
            id="user-filter-search"
            className="form-control form-control-sm"
            placeholder={t('users.searchPlaceholder')}
            value={draftFilters.search}
            onChange={(event) => setDraftFilters({ ...draftFilters, search: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="user-filter-access" className="form-label small mb-1">
            {tc('columns.status')}
          </label>
          <Select
            id="user-filter-access"
            size="sm"
            value={draftFilters.access}
            onChange={(next) => setDraftFilters({ ...draftFilters, access: next as AccessFilter })}
            options={[
              { value: 'active', label: t('users.hasAccess') },
              { value: 'revoked', label: t('users.accessRevoked') },
              { value: 'all', label: tc('filters.statusAll') },
            ]}
          />
        </div>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(user) => user.membershipId}
        isLoading={usersQuery.isPending}
        error={usersQuery.isError ? describeApiError(usersQuery.error as ApiError) : null}
        onRetry={() => void usersQuery.refetch()}
        emptyTitle={t('users.empty.title')}
        emptyMessage={t('users.empty.message')}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <UserFormDrawer
          companyId={companyId}
          user={modal.mode === 'edit' ? modal.user : null}
          roles={rolesQuery.data ?? []}
          currentAppUserId={profile?.id ?? null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? td('updated') : t('users.invited'))
            refresh()
          }}
        />
      )}
    </div>
  )
}
