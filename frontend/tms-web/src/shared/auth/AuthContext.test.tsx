import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../api/httpClient'
import { AuthProvider, useAuth } from './AuthContext'

const authMocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  onAuthStateChange: vi.fn(),
  signInWithPassword: vi.fn(),
  signOut: vi.fn(),
}))

vi.mock('./supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: authMocks.getSession,
      onAuthStateChange: authMocks.onAuthStateChange,
      signInWithPassword: authMocks.signInWithPassword,
      signOut: authMocks.signOut,
    },
  },
}))

function session(email: string) {
  return { access_token: 'token-abc', user: { id: 'user-1', email } }
}

function Probe() {
  const { status, user } = useAuth()
  return (
    <div data-testid="probe">
      {status}:{user?.email ?? 'none'}
    </div>
  )
}

function renderAuthProvider() {
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('AuthProvider', () => {
  it('resolves signedIn from an existing Supabase session', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test') } })
    authMocks.onAuthStateChange.mockReturnValue({ data: { subscription: { unsubscribe: vi.fn() } } })

    renderAuthProvider()

    expect(await screen.findByText('signedIn:driver@ebim.test')).toBeInTheDocument()
  })

  it('resolves signedOut when there is no session', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: null } })
    authMocks.onAuthStateChange.mockReturnValue({ data: { subscription: { unsubscribe: vi.fn() } } })

    renderAuthProvider()

    expect(await screen.findByText('signedOut:none')).toBeInTheDocument()
  })

  it('registers a token provider so the API client attaches the Supabase access token', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test') } })
    authMocks.onAuthStateChange.mockReturnValue({ data: { subscription: { unsubscribe: vi.fn() } } })

    renderAuthProvider()
    await screen.findByText('signedIn:driver@ebim.test')

    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }))

    await apiRequest('/me')

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer token-abc')
  })

  it('signs out exactly once when the backend answers 401, even for several failures at once', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: session('driver@ebim.test') } })
    authMocks.onAuthStateChange.mockReturnValue({ data: { subscription: { unsubscribe: vi.fn() } } })
    authMocks.signOut.mockResolvedValue({ error: null })

    renderAuthProvider()
    await screen.findByText('signedIn:driver@ebim.test')

    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ code: 'unauthenticated', detail: 'Session expired' }), {
        status: 401,
        headers: { 'content-type': 'application/json' },
      }),
    )

    await act(async () => {
      await Promise.allSettled([apiRequest('/orders'), apiRequest('/trips')])
    })

    await waitFor(() => expect(authMocks.signOut).toHaveBeenCalledTimes(1))
  })

  it('signIn reports a friendly failure without throwing when Supabase rejects the credentials', async () => {
    authMocks.getSession.mockResolvedValue({ data: { session: null } })
    authMocks.onAuthStateChange.mockReturnValue({ data: { subscription: { unsubscribe: vi.fn() } } })
    authMocks.signInWithPassword.mockResolvedValue({ error: { message: 'Invalid login credentials' } })

    let signInResult: { ok: boolean; message?: string } | undefined
    function SignInProbe() {
      const { signIn } = useAuth()
      return (
        <button
          type="button"
          onClick={() => {
            void signIn('driver@ebim.test', 'wrong-password').then((result) => {
              signInResult = result
            })
          }}
        >
          go
        </button>
      )
    }

    render(
      <AuthProvider>
        <SignInProbe />
      </AuthProvider>,
    )

    screen.getByText('go').click()

    await waitFor(() => expect(signInResult).toEqual({ ok: false, message: 'Invalid login credentials' }))
  })
})
