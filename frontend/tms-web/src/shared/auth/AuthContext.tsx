import type { Session } from '@supabase/supabase-js'
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  onApiResponseError,
  setAuthRefreshHandler,
  setAuthTokenProvider,
  type ApiError,
} from '../api/httpClient'
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
 *
 * The session is mirrored into a ref so the token can be read synchronously. Asking
 * `supabase.auth.getSession()` once per request instead made the token arrive an await (and a
 * `navigator.locks` acquisition) later than the `signedIn` status it belongs to: the first
 * request after a sign-in could therefore go out with no `Authorization` header, collect the
 * backend's 401 and tear down a session that was perfectly valid. Holding the session here
 * removes the ordering entirely - `status` and the token it corresponds to are published in
 * the same commit.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading')
  const [user, setUser] = useState<AuthUser | null>(null)

  /** The session every outgoing request is authenticated with. */
  const sessionRef = useRef<Session | null>(null)
  // Guards against a signOut-triggers-401-triggers-signOut loop: once a forced sign-out is
  // underway, further auth failures from in-flight requests are ignored until a session
  // actually arrives again.
  const signingOutRef = useRef(false)

  const applySession = useCallback((session: Session | null) => {
    sessionRef.current = session
    if (session) {
      signingOutRef.current = false
    }
    setUser(toAuthUser(session))
    setStatus(session ? 'signedIn' : 'signedOut')
  }, [])

  /**
   * The controlled recovery `httpClient` calls after an authentication failure. Returning a
   * token means "replay the request once"; returning `null` means the session is gone and the
   * caller should surface the failure, which is what ends up signing the user out.
   */
  const refreshAccessToken = useCallback(async (): Promise<string | null> => {
    try {
      const { data, error } = await supabase.auth.refreshSession()
      if (error || !data?.session) {
        return null
      }
      applySession(data.session)
      return data.session.access_token ?? null
    } catch {
      return null
    }
  }, [applySession])

  useEffect(() => {
    let active = true

    // Registered before the session is resolved. Nothing can issue a business request until
    // `status` becomes `signedIn`, and that only happens below, so the provider is always in
    // place by the time the first request is built.
    setAuthTokenProvider(() => sessionRef.current?.access_token ?? null)
    setAuthRefreshHandler(refreshAccessToken)

    const { data: listener } = supabase.auth.onAuthStateChange((_event, session) => {
      if (!active) {
        return
      }
      applySession(session)
    })

    void supabase.auth.getSession().then(({ data }) => {
      // A sign-in may already have published a newer session while this was in flight; the
      // stored one must not overwrite it.
      if (!active || sessionRef.current) {
        return
      }
      applySession(data.session ?? null)
    })

    return () => {
      active = false
      listener.subscription.unsubscribe()
    }
  }, [applySession, refreshAccessToken])

  useEffect(() => {
    return onApiResponseError((error: ApiError) => {
      // `httpClient` reports an authentication failure only once its refresh+retry has already
      // failed, so reaching this point means the session could not be recovered. A failure
      // while no session is held is not ours to react to - it cannot be fixed by signing out.
      if (!isAuthProblem(error) || signingOutRef.current || sessionRef.current === null) {
        return
      }
      signingOutRef.current = true
      sessionRef.current = null
      void supabase.auth.signOut()
    })
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      async signIn(email, password) {
        const { data, error } = await supabase.auth.signInWithPassword({ email, password })
        if (error) {
          return { ok: false, message: error.message }
        }
        // Adopt the session from the call that produced it rather than waiting for
        // `onAuthStateChange`. Both fire, but only this ordering guarantees the token is
        // readable the instant `status` turns `signedIn`.
        if (data?.session) {
          applySession(data.session)
        }
        return { ok: true }
      },
      async signOut() {
        signingOutRef.current = true
        sessionRef.current = null
        await supabase.auth.signOut()
        setUser(null)
        setStatus('signedOut')
      },
    }),
    [applySession, status, user],
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
