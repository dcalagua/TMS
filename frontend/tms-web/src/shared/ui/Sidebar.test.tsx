import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { Sidebar } from './Sidebar'

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))

/** The sidebar now hosts the company switcher, so a mock has to carry the switcher's slice of
 * the context too. These tests are about capability gating; an empty list keeps the control
 * inert without pulling its own behaviour into their assertions. */
const switcherSlice = { companies: [], selected: null, selectCompany: () => {} }
vi.mock('../company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

function renderSidebar() {
  return render(
    <MemoryRouter>
      <Sidebar open={false} collapsed={false} onRequestClose={() => {}} />
    </MemoryRouter>,
  )
}

describe('Sidebar', () => {
  it('always shows Dashboard, which needs no capability', () => {
    companyMocks.useCompany.mockReturnValue({ ...switcherSlice, status: 'ready', hasCapability: () => false })

    renderSidebar()

    expect(screen.getByRole('link', { name: 'Inicio' })).toBeInTheDocument()
  })

  it('hides a module group once the selected company is known to lack its capability', () => {
    companyMocks.useCompany.mockReturnValue({
      ...switcherSlice,
      status: 'ready',
      hasCapability: (capability: string) => capability === 'FLEET_VIEW',
    })

    renderSidebar()

    expect(screen.queryByText('Maestros')).not.toBeInTheDocument()
    expect(screen.getByText('Flota')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Transportistas' })).toBeInTheDocument()
  })

  it('shows every group while the company is still loading, instead of flickering empty then full', () => {
    companyMocks.useCompany.mockReturnValue({ ...switcherSlice, status: 'loading', hasCapability: () => false })

    renderSidebar()

    expect(screen.getByText('Maestros')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Pedidos' })).toBeInTheDocument()
    // Configuración is a group again since job 12 - two screens, in the trailing slot below the
    // modules - so the assertion is on its heading and on one of its entries.
    expect(screen.getByText('Configuración')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Usuarios y accesos' })).toBeInTheDocument()
  })

  it('hides Configuración from a company with no IAM capability', () => {
    companyMocks.useCompany.mockReturnValue({
      ...switcherSlice,
      status: 'ready',
      hasCapability: (capability: string) => capability === 'ORDERS_VIEW',
    })

    renderSidebar()

    expect(screen.queryByText('Configuración')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Compañía' })).not.toBeInTheDocument()
  })
})
