import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { DEFAULT_LANGUAGE } from '../i18n/config'
import { NavSearch } from './NavSearch'

const companyMocks = vi.hoisted(() => ({ hasCapability: vi.fn(), status: 'ready' as string }))
vi.mock('../company/CompanyContext', () => ({
  useCompany: () => ({ hasCapability: companyMocks.hasCapability, status: companyMocks.status }),
}))

function renderSearch() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <NavSearch />
      <Routes>
        <Route path="/" element={<p>Inicio</p>} />
        <Route path="/masters/frequencies" element={<p>Pantalla de frecuencias</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(async () => {
  vi.clearAllMocks()
  companyMocks.status = 'ready'
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('NavSearch', () => {
  it('offers nothing until something is typed', async () => {
    companyMocks.hasCapability.mockReturnValue(true)
    const user = userEvent.setup()
    renderSearch()

    await user.click(screen.getByRole('combobox'))

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('matches a screen by name, ignoring accents and case', async () => {
    companyMocks.hasCapability.mockReturnValue(true)
    const user = userEvent.setup()
    renderSearch()

    // "Frecuencias" typed without its accent-free stem still has to match.
    await user.type(screen.getByRole('combobox'), 'FREC')

    const options = await screen.findAllByRole('option')
    expect(options).toHaveLength(1)
    expect(options[0]).toHaveTextContent('Frecuencias')
    // The group is shown alongside, so two similarly named screens stay distinguishable.
    expect(options[0]).toHaveTextContent('Maestros')
  })

  it('navigates to the screen that is chosen, and clears itself', async () => {
    companyMocks.hasCapability.mockReturnValue(true)
    const user = userEvent.setup()
    renderSearch()

    await user.type(screen.getByRole('combobox'), 'frecuencias')
    await user.click(await screen.findByRole('option', { name: /Frecuencias/ }))

    expect(await screen.findByText('Pantalla de frecuencias')).toBeInTheDocument()
    expect(screen.getByRole('combobox')).toHaveValue('')
  })

  it('is driven from the keyboard', async () => {
    companyMocks.hasCapability.mockReturnValue(true)
    const user = userEvent.setup()
    renderSearch()

    await user.type(screen.getByRole('combobox'), 'frecuencias')
    await screen.findByRole('option', { name: /Frecuencias/ })
    await user.keyboard('{Enter}')

    expect(await screen.findByText('Pantalla de frecuencias')).toBeInTheDocument()
  })

  it('says so when nothing matches', async () => {
    companyMocks.hasCapability.mockReturnValue(true)
    const user = userEvent.setup()
    renderSearch()

    await user.type(screen.getByRole('combobox'), 'zzzz')

    expect(await screen.findByText('Sin coincidencias')).toBeInTheDocument()
  })

  it('never offers a screen the menu is hiding', async () => {
    // The sidebar gates Maestros behind MASTER_DATA_VIEW; the search has to gate it identically,
    // or it becomes a way around the navigation's own capability check.
    companyMocks.hasCapability.mockImplementation((capability: string) => capability !== 'MASTER_DATA_VIEW')
    const user = userEvent.setup()
    renderSearch()

    await user.type(screen.getByRole('combobox'), 'frecuencias')

    await waitFor(() => expect(screen.getByText('Sin coincidencias')).toBeInTheDocument())
  })

  it('searches in the active language', async () => {
    await i18n.changeLanguage('en')
    companyMocks.hasCapability.mockReturnValue(true)
    const user = userEvent.setup()
    renderSearch()

    await user.type(screen.getByRole('combobox'), 'frequenc')

    expect(await screen.findByRole('option', { name: /Frequencies/ })).toBeInTheDocument()
  })

  it('closes on Escape without losing what was typed', async () => {
    companyMocks.hasCapability.mockReturnValue(true)
    const user = userEvent.setup()
    renderSearch()

    await user.type(screen.getByRole('combobox'), 'frec')
    await screen.findByRole('listbox')
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(screen.getByRole('combobox')).toHaveValue('frec')
  })
})
