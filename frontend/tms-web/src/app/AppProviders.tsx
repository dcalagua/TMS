import { QueryClientProvider } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { AuthProvider } from '../shared/auth/AuthContext'
import { CompanyProvider } from '../shared/company/CompanyContext'
import { createQueryClient } from './queryClient'

/** Wraps the application in its cross-cutting providers, innermost dependency first: the
 * company context reads auth status and issues queries, so it sits inside both. */
export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(createQueryClient)

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <CompanyProvider>{children}</CompanyProvider>
      </AuthProvider>
    </QueryClientProvider>
  )
}
