import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CompanySelector } from './CompanySelector'

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

describe('CompanySelector', () => {
  it('offers only the companies the backend returned and reports the choice', async () => {
    const selectCompany = vi.fn()
    companyMocks.useCompany.mockReturnValue({
      status: 'ready',
      companies: [
        { id: 'c1', name: 'Acme Logistics', organization: { name: 'Acme' } },
        { id: 'c2', name: 'Acme West', organization: { name: 'Acme' } },
      ],
      selected: { id: 'c1', name: 'Acme Logistics' },
      selectCompany,
    })

    render(<CompanySelector />)
    await userEvent.click(screen.getByRole('button', { name: /Acme Logistics/ }))
    // The entries are radio menu items: exactly one company is the active scope.
    expect(screen.getByRole('menuitemradio', { name: /Acme Logistics/ })).toHaveAttribute('aria-checked', 'true')
    await userEvent.click(screen.getByRole('menuitemradio', { name: /Acme West/ }))

    expect(selectCompany).toHaveBeenCalledWith('c2')
  })

  it('shows a warning instead of a picker when the account has no company access', () => {
    companyMocks.useCompany.mockReturnValue({ status: 'ready', companies: [], selected: null, selectCompany: vi.fn() })

    render(<CompanySelector />)

    expect(screen.getByText('Sin acceso a compañías')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
