import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { Sidebar } from './Sidebar'

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

function renderSidebar() {
  return render(
    <MemoryRouter>
      <Sidebar open={false} onRequestClose={() => {}} />
    </MemoryRouter>,
  )
}

describe('Sidebar', () => {
  it('always shows Dashboard, which needs no capability', () => {
    companyMocks.useCompany.mockReturnValue({ status: 'ready', hasCapability: () => false })

    renderSidebar()

    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeInTheDocument()
  })

  it('hides a module group once the selected company is known to lack its capability', () => {
    companyMocks.useCompany.mockReturnValue({
      status: 'ready',
      hasCapability: (capability: string) => capability === 'FLEET_VIEW',
    })

    renderSidebar()

    expect(screen.queryByText('Master Data')).not.toBeInTheDocument()
    expect(screen.getByText('Fleet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Carriers' })).toBeInTheDocument()
  })

  it('shows every group while the company is still loading, instead of flickering empty then full', () => {
    companyMocks.useCompany.mockReturnValue({ status: 'loading', hasCapability: () => false })

    renderSidebar()

    expect(screen.getByText('Master Data')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Orders' })).toBeInTheDocument()
    expect(screen.getByText('Administration')).toBeInTheDocument()
  })
})
