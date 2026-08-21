import { QueryClientProvider } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { AuthProvider } from '../shared/auth/AuthContext'
import { CompanyProvider } from '../shared/company/CompanyContext'
import { ThemeProvider } from '../shared/theme/ThemeProvider'
import { createQueryClient } from './queryClient'

/** Wraps the application in its cross-cutting providers, innermost dependency first: the
 * company context reads auth status and issues queries, so it sits inside both.
 *
 * The theme sits outermost and depends on nothing: it must apply to the sign-in screen and to
 * an error boundary just as much as to an authenticated session. */
export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(createQueryClient)

  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <CompanyProvider>{children}</CompanyProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>
  )
}
