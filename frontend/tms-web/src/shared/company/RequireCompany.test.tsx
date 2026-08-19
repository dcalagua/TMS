import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { RequireCompany } from './RequireCompany'

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('./CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

function renderGuarded() {
  return render(
    <MemoryRouter initialEntries={['/masters/origins']}>
      <Routes>
        <Route element={<RequireCompany />}>
          <Route path="/masters/origins" element={<div>Origins screen</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('RequireCompany', () => {
  it('renders the screen once a company is selected', () => {
    companyMocks.useCompany.mockReturnValue({
      status: 'ready',
      companies: [{ id: 'c1', name: 'Acme Logistics' }],
      selected: { id: 'c1', name: 'Acme Logistics' },
      errorMessage: null,
      refetch: vi.fn(),
    })

    renderGuarded()

    expect(screen.getByText('Origins screen')).toBeInTheDocument()
  })

  it('shows an empty state instead of the screen when the account has no company access', () => {
    companyMocks.useCompany.mockReturnValue({
      status: 'ready',
      companies: [],
      selected: null,
      errorMessage: null,
      refetch: vi.fn(),
    })

    renderGuarded()

    expect(screen.getByText('Sin acceso a compañías')).toBeInTheDocument()
    expect(screen.queryByText('Origins screen')).not.toBeInTheDocument()
  })

  it('shows a retryable error instead of the screen when /me fails', () => {
    const refetch = vi.fn()
    companyMocks.useCompany.mockReturnValue({
      status: 'error',
      companies: [],
      selected: null,
      errorMessage: 'Ocurrió un error de nuestro lado. Vuelve a intentarlo.',
      refetch,
    })

    renderGuarded()

    expect(screen.getByText('Ocurrió un error de nuestro lado. Vuelve a intentarlo.')).toBeInTheDocument()
    expect(screen.queryByText('Origins screen')).not.toBeInTheDocument()
  })
})
