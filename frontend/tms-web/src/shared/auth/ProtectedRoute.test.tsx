import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ProtectedRoute } from './ProtectedRoute'

const authMocks = vi.hoisted(() => ({ useAuth: vi.fn() }))
vi.mock('./AuthContext', () => ({ useAuth: authMocks.useAuth }))

function renderProtected() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/login" element={<div>Login screen</div>} />
        <Route element={<ProtectedRoute />}>
          <Route path="/" element={<div>Protected home</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProtectedRoute', () => {
  it('shows a loading state while the session is being resolved, instead of the guarded content', () => {
    authMocks.useAuth.mockReturnValue({ status: 'loading' })

    renderProtected()

    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.queryByText('Protected home')).not.toBeInTheDocument()
  })

  it('redirects to /login when signed out', () => {
    authMocks.useAuth.mockReturnValue({ status: 'signedOut' })

    renderProtected()

    expect(screen.getByText('Login screen')).toBeInTheDocument()
    expect(screen.queryByText('Protected home')).not.toBeInTheDocument()
  })

  it('renders the protected content when signed in', () => {
    authMocks.useAuth.mockReturnValue({ status: 'signedIn' })

    renderProtected()

    expect(screen.getByText('Protected home')).toBeInTheDocument()
  })
})
