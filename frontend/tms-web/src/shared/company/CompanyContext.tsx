import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { onApiResponseError, type ApiError } from '../api/httpClient'
import { fetchMe, type CompanyAccessView, type MeView } from '../api/meApi'
import { describeApiError, isCompanyScopeStale } from '../api/problemMessages'
import { useAuth } from '../auth/AuthContext'
import { notifyError } from '../ui/alerts'

const STORAGE_KEY = 'tms.selectedCompanyId'

export type CompanyStatus = 'idle' | 'loading' | 'error' | 'ready'

interface CompanyContextValue {
  status: CompanyStatus
  companies: CompanyAccessView[]
  selected: CompanyAccessView | null
  selectCompany: (companyId: string) => void
  hasPermission: (permission: string) => boolean
  hasCapability: (capability: string) => boolean
  errorMessage: string | null
  refetch: () => void
}

const CompanyContext = createContext<CompanyContextValue | null>(null)

function readStoredCompanyId(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function storeCompanyId(companyId: string | null): void {
  try {
    if (companyId) {
      window.localStorage.setItem(STORAGE_KEY, companyId)
    } else {
      window.localStorage.removeItem(STORAGE_KEY)
    }
  } catch {
    // Storage may be unavailable (private mode, disabled cookies). The selector still works
    // for the current tab; it just does not survive a reload.
  }
}

/**
 * Company context/provider: the single source of truth for which companies the signed-in
 * user may operate in. It reflects `GET /api/v1/me` exactly - nothing here manufactures
 * tenant access. Selecting a company only changes which `X-Company-Id` later requests send;
 * the backend independently validates that header on every company-scoped call.
 */
export function CompanyProvider({ children }: { children: ReactNode }) {
  const { status: authStatus } = useAuth()
  const queryClient = useQueryClient()
  const [selectedId, setSelectedId] = useState<string | null>(readStoredCompanyId)

  // Clears the selection the moment sign-out happens. Adjusted during render (React's
  // documented pattern for "reset state when a value changes") rather than in an effect, so a
  // sign-out/sign-in cycle cannot commit a frame with the previous user's company still set.
  const [trackedAuthStatus, setTrackedAuthStatus] = useState(authStatus)
  if (authStatus !== trackedAuthStatus) {
    setTrackedAuthStatus(authStatus)
    if (authStatus !== 'signedIn' && selectedId !== null) {
      setSelectedId(null)
    }
  }

  const meQuery = useQuery<MeView, ApiError>({
    queryKey: ['me'],
    queryFn: ({ signal }) => fetchMe(signal),
    enabled: authStatus === 'signedIn',
    staleTime: 60_000,
  })

  useEffect(() => {
    return onApiResponseError((error: ApiError) => {
      if (!isCompanyScopeStale(error)) {
        return
      }
      notifyError('Company access changed', 'Your selection is no longer valid. Choose a company again.')
      setSelectedId(null)
      storeCompanyId(null)
      void queryClient.invalidateQueries({ queryKey: ['me'] })
    })
  }, [queryClient])

  const companies = useMemo(() => meQuery.data?.companies ?? [], [meQuery.data])

  const selected = useMemo(() => {
    if (companies.length === 0) {
      return null
    }
    const found = selectedId ? companies.find((company) => company.id === selectedId) : undefined
    return found ?? companies[0] ?? null
  }, [companies, selectedId])

  useEffect(() => {
    storeCompanyId(selected?.id ?? null)
  }, [selected])

  const status: CompanyStatus =
    authStatus !== 'signedIn' ? 'idle' : meQuery.isPending ? 'loading' : meQuery.isError ? 'error' : 'ready'

  const value: CompanyContextValue = {
    status,
    companies,
    selected,
    selectCompany: setSelectedId,
    hasPermission: (permission) => selected?.permissions.includes(permission) ?? false,
    hasCapability: (capability) => selected?.capabilities.includes(capability) ?? false,
    errorMessage: meQuery.error ? describeApiError(meQuery.error) : null,
    refetch: () => void meQuery.refetch(),
  }

  return <CompanyContext.Provider value={value}>{children}</CompanyContext.Provider>
}

export function useCompany(): CompanyContextValue {
  const context = useContext(CompanyContext)
  if (!context) {
    throw new Error('useCompany must be used within a CompanyProvider')
  }
  return context
}
