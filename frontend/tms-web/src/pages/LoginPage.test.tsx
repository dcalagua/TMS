import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'

const authMocks = vi.hoisted(() => ({ useAuth: vi.fn() }))
vi.mock('../shared/auth/AuthContext', () => ({ useAuth: authMocks.useAuth }))

function renderLogin() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<div>Dashboard</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('LoginPage', () => {
  it('calls signIn with the entered credentials', async () => {
    const signIn = vi.fn().mockResolvedValue({ ok: true })
    authMocks.useAuth.mockReturnValue({ status: 'signedOut', signIn })

    renderLogin()
    await userEvent.type(screen.getByLabelText('Email', { exact: false }), 'driver@ebim.test')
    await userEvent.type(screen.getByLabelText('Password', { exact: false }), 'correct-horse')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(signIn).toHaveBeenCalledWith('driver@ebim.test', 'correct-horse')
  })

  it('shows the message signIn returns on failure, without navigating away', async () => {
    const signIn = vi.fn().mockResolvedValue({ ok: false, message: 'Invalid login credentials' })
    authMocks.useAuth.mockReturnValue({ status: 'signedOut', signIn })

    renderLogin()
    await userEvent.type(screen.getByLabelText('Email', { exact: false }), 'driver@ebim.test')
    await userEvent.type(screen.getByLabelText('Password', { exact: false }), 'wrong-password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Invalid login credentials')).toBeInTheDocument()
    expect(screen.queryByText('Dashboard')).not.toBeInTheDocument()
  })

  it('redirects away when the user is already signed in', () => {
    authMocks.useAuth.mockReturnValue({ status: 'signedIn', signIn: vi.fn() })

    renderLogin()

    expect(screen.getByText('Dashboard')).toBeInTheDocument()
  })
})
