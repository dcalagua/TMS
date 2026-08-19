import type { Session } from '@supabase/supabase-js'
import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { onApiResponseError, setAuthTokenProvider, type ApiError } from '../api/httpClient'
import { isAuthProblem } from '../api/problemMessages'
import { supabase } from './supabaseClient'

export type AuthStatus = 'loading' | 'signedOut' | 'signedIn'

export interface AuthUser {
  id: string
  email: string | null
}

export interface SignInResult {
  ok: boolean
  message?: string
}

interface AuthContextValue {
  status: AuthStatus
  user: AuthUser | null
  signIn(email: string, password: string): Promise<SignInResult>
  signOut(): Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

function toAuthUser(session: Session | null): AuthUser | null {
  if (!session?.user) {
    return null
  }
  return { id: session.user.id, email: session.user.email ?? null }
}

/**
 * Supabase Auth abstraction: login/logout/session refresh live here and nowhere else.
 * `httpClient` gets its bearer token through {@link setAuthTokenProvider}; business screens
 * never touch the Supabase client directly (V1 rule: Supabase is authentication only).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading')
  const [user, setUser] = useState<AuthUser | null>(null)
  // Guards against a signOut-triggers-401-triggers-signOut loop: once a forced sign-out is
  // underway, further auth failures from in-flight requests are ignored until the session
  // actually changes.
  const signingOutRef = useRef(false)

  useEffect(() => {
    setAuthTokenProvider(async () => {
      const { data } = await supabase.auth.getSession()
      return data.session?.access_token ?? null
    })
  }, [])

  useEffect(() => {
    let active = true

    void supabase.auth.getSession().then(({ data }) => {
      if (!active) {
        return
      }
      setUser(toAuthUser(data.session))
      setStatus(data.session ? 'signedIn' : 'signedOut')
    })

    const { data: listener } = supabase.auth.onAuthStateChange((_event, session) => {
      if (!active) {
        return
      }
      setUser(toAuthUser(session))
      setStatus(session ? 'signedIn' : 'signedOut')
      signingOutRef.current = false
    })

    return () => {
      active = false
      listener.subscription.unsubscribe()
    }
  }, [])

  useEffect(() => {
    return onApiResponseError((error: ApiError) => {
      if (!isAuthProblem(error) || signingOutRef.current) {
        return
      }
      signingOutRef.current = true
      void supabase.auth.signOut()
    })
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      async signIn(email, password) {
        const { error } = await supabase.auth.signInWithPassword({ email, password })
        if (error) {
          return { ok: false, message: error.message }
        }
        return { ok: true }
      },
      async signOut() {
        await supabase.auth.signOut()
      },
    }),
    [status, user],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
