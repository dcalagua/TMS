import type { Session } from '@supabase/supabase-js'
import { act, render, screen, waitFor } from '@testing-library/react'
import { useEffect } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, resetAuthRefreshState, setAuthTokenProvider } from '../api/httpClient'
import { AuthProvider, useAuth, type AuthStatus } from './AuthContext'

const authMocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  onAuthStateChange: vi.fn(),
  signInWithPassword: vi.fn(),
  signOut: vi.fn(),
  refreshSession: vi.fn(),
}))

vi.mock('./supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: authMocks.getSession,
      onAuthStateChange: authMocks.onAuthStateChange,
      signInWithPassword: authMocks.signInWithPassword,
      signOut: authMocks.signOut,
      refreshSession: authMocks.refreshSession,
    },
  },
}))

function session(email: string, accessToken = 'token-abc'): Session {
  return { access_token: accessToken, user: { id: 'user-1', email } } as unknown as Session
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function unauthenticated(): Response {
  return jsonResponse({ code: 'unauthenticated', detail: 'Full authentication is required' }, 401)
}

/** Captures the context value and every status the provider has rendered, so a test can assert
 * on the sequence of transitions and not only on the final one. */
let auth: ReturnType<typeof useAuth> | null = null
let renderedStatuses: AuthStatus[] = []

function Probe() {
  const value = useAuth()
  if (renderedStatuses.at(-1) !== value.status) {
    renderedStatuses.push(value.status)
  }
  useEffect(() => {
    auth = value
  }, [value])
  return <div data-testid="probe">{value.status}:{value.user?.email ?? 'none'}</div>
}

function renderAuthProvider() {
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
}

/** The listener supabase-js would install; tests can push events through it. */
function captureAuthListener() {
  let emit: ((event: string, next: Session | null) => void) | null = null
  authMocks.onAuthStateChange.mockImplementation((callback: (event: string, next: Session | null) => void) => {
    emit = callback
    return { data: { subscription: { unsubscribe: vi.fn() } } }
  })
  return {
    emit: (event: string, next: Session | null) => {
      if (!emit) {
        throw new Error('onAuthStateChange was never subscribed')
      }
      emit(event, next)
    },
  }
}

beforeEach(() => {
  auth = null
  renderedStatuses = []
  authMocks.onAuthStateChange.mockReturnValue({ data: { subscription: { unsubscribe: vi.fn() } } })
  authMocks.getSession.mockResolvedValue({ data: { session: null } })
  authMocks.signOut.mockResolvedValue({ error: null })
  authMocks.refreshSession.mockResolvedValue({ data: { session: null }, error: { message: 'no session' } })
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  resetAuthRefreshState()
  setAuthTokenProvider(() => null)
})

describe('AuthProvider session resolution', () => {
  it('resolves signedIn from an existing Supabase session', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test') } })

    renderAuthProvider()

    expect(await screen.findByText('signedIn:driver@ebim.test')).toBeInTheDocument()
  })

  it('resolves signedOut when there is no session', async () => {
    renderAuthProvider()

    expect(await screen.findByText('signedOut:none')).toBeInTheDocument()
  })

  it('restores a persisted session on reload, so a refresh does not bounce to the login screen', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test') } })

    const first = renderAuthProvider()
    await screen.findByText('signedIn:driver@ebim.test')
    first.unmount()

    // A reload is a fresh mount reading the same persisted session.
    renderedStatuses = []
    renderAuthProvider()

    expect(await screen.findByText('signedIn:driver@ebim.test')).toBeInTheDocument()
    expect(renderedStatuses).not.toContain('signedOut')
  })

  it('registers a token provider so the API client attaches the Supabase access token', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test') } })

    renderAuthProvider()
    await screen.findByText('signedIn:driver@ebim.test')

    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({}))
    await apiRequest('/me')

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer token-abc')
  })
})

describe('first sign-in', () => {
  it('authenticates its very first backend request and stays signed in', async () => {
    // The original defect in one test: the token came from `supabase.auth.getSession()`, which
    // had not caught up with the sign-in that had just happened, so `GET /me` went out with no
    // Authorization header, collected a 401 and destroyed a perfectly valid session. The token
    // must come from the session `signInWithPassword` itself returned.
    authMocks.getSession.mockResolvedValue({ data: { session: null } })
    authMocks.signInWithPassword.mockResolvedValue({
      data: { session: session('planner@ebim.test', 'fresh-token') },
      error: null,
    })

    renderAuthProvider()
    await screen.findByText('signedOut:none')

    await act(async () => {
      await auth?.signIn('planner@ebim.test', 'correct-password')
    })
    await screen.findByText('signedIn:planner@ebim.test')

    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const headers = (init?.headers ?? {}) as Record<string, string>
      return headers.Authorization === 'Bearer fresh-token'
        ? jsonResponse({ user: { id: 'user-1' }, companies: [] })
        : unauthenticated()
    })

    await act(async () => {
      await apiRequest('/me')
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(authMocks.signOut).not.toHaveBeenCalled()
    expect(await screen.findByText('signedIn:planner@ebim.test')).toBeInTheDocument()
  })

  it('never flashes signedIn then signedOut on a single valid sign-in', async () => {
    const listener = captureAuthListener()
    authMocks.signInWithPassword.mockResolvedValue({
      data: { session: session('planner@ebim.test', 'fresh-token') },
      error: null,
    })

    renderAuthProvider()
    await screen.findByText('signedOut:none')

    await act(async () => {
      await auth?.signIn('planner@ebim.test', 'correct-password')
    })
    // supabase-js also announces the sign-in through the listener; it must not add a transition.
    await act(async () => {
      listener.emit('SIGNED_IN', session('planner@ebim.test', 'fresh-token'))
    })

    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ companies: [] }))
    await act(async () => {
      await apiRequest('/me')
    })

    expect(renderedStatuses).toEqual(['loading', 'signedOut', 'signedIn'])
    expect(authMocks.signOut).not.toHaveBeenCalled()
  })

  it('reports a friendly failure without throwing when Supabase rejects the credentials', async () => {
    authMocks.signInWithPassword.mockResolvedValue({ data: { session: null }, error: { message: 'Invalid login credentials' } })

    renderAuthProvider()
    await screen.findByText('signedOut:none')

    let result: { ok: boolean; message?: string } | undefined
    await act(async () => {
      result = await auth?.signIn('planner@ebim.test', 'wrong-password')
    })

    expect(result).toEqual({ ok: false, message: 'Invalid login credentials' })
    expect(screen.getByTestId('probe')).toHaveTextContent('signedOut:none')
  })
})

describe('authentication failures', () => {
  it('recovers a transient 401 with one refresh instead of signing out', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test', 'stale-token') } })
    authMocks.refreshSession.mockResolvedValue({
      data: { session: session('driver@ebim.test', 'renewed-token') },
      error: null,
    })

    renderAuthProvider()
    await screen.findByText('signedIn:driver@ebim.test')

    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const headers = (init?.headers ?? {}) as Record<string, string>
      return headers.Authorization === 'Bearer renewed-token' ? jsonResponse({ ok: true }) : unauthenticated()
    })

    let payload: unknown
    await act(async () => {
      payload = await apiRequest('/orders')
    })

    expect(payload).toEqual({ ok: true })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(authMocks.refreshSession).toHaveBeenCalledTimes(1)
    expect(authMocks.signOut).not.toHaveBeenCalled()
    expect(screen.getByTestId('probe')).toHaveTextContent('signedIn:driver@ebim.test')
  })

  it('retries a rejected request at most once, then signs out - never in a loop', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test', 'stale-token') } })
    authMocks.refreshSession.mockResolvedValue({
      data: { session: session('driver@ebim.test', 'renewed-token') },
      error: null,
    })

    renderAuthProvider()
    await screen.findByText('signedIn:driver@ebim.test')

    // The backend rejects both the original token and the renewed one: the session is genuinely
    // dead, so the app gives up rather than refreshing forever.
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => unauthenticated())

    await act(async () => {
      await expect(apiRequest('/orders')).rejects.toMatchObject({ status: 401 })
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    await waitFor(() => expect(authMocks.signOut).toHaveBeenCalledTimes(1))
  })

  it('shares one refresh and one sign-out across several requests failing at the same instant', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test', 'stale-token') } })

    renderAuthProvider()
    await screen.findByText('signedIn:driver@ebim.test')

    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => unauthenticated())

    await act(async () => {
      await Promise.allSettled([apiRequest('/orders'), apiRequest('/trips'), apiRequest('/vehicles')])
    })

    expect(authMocks.refreshSession).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(authMocks.signOut).toHaveBeenCalledTimes(1))
  })

  it('does not sign out over a 401 raised while no session is held', async () => {
    renderAuthProvider()
    await screen.findByText('signedOut:none')

    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => unauthenticated())

    await act(async () => {
      await expect(apiRequest('/me')).rejects.toMatchObject({ status: 401 })
    })

    expect(authMocks.signOut).not.toHaveBeenCalled()
  })
})

describe('session lifecycle', () => {
  it('adopts a refreshed token from onAuthStateChange for subsequent requests', async () => {
    const listener = captureAuthListener()
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test', 'first-token') } })

    renderAuthProvider()
    await screen.findByText('signedIn:driver@ebim.test')

    await act(async () => {
      listener.emit('TOKEN_REFRESHED', session('driver@ebim.test', 'second-token'))
    })

    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({}))
    await apiRequest('/orders')

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer second-token')
  })

  it('signs out on request and stops sending the old token', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test') } })

    renderAuthProvider()
    await screen.findByText('signedIn:driver@ebim.test')

    await act(async () => {
      await auth?.signOut()
    })

    expect(authMocks.signOut).toHaveBeenCalledTimes(1)
    expect(await screen.findByText('signedOut:none')).toBeInTheDocument()

    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({}))
    await apiRequest('/system/info')

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>
    expect(headers.Authorization).toBeUndefined()
  })

  it('signs the user out when supabase reports the session ended elsewhere', async () => {
    const listener = captureAuthListener()
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test') } })

    renderAuthProvider()
    await screen.findByText('signedIn:driver@ebim.test')

    await act(async () => {
      listener.emit('SIGNED_OUT', null)
    })

    expect(await screen.findByText('signedOut:none')).toBeInTheDocument()
  })
})
