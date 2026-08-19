import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import i18n from '../i18n'
import { DEFAULT_LANGUAGE } from '../i18n/config'
import { Breadcrumb } from './Breadcrumb'

function renderAt(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <Breadcrumb />
    </MemoryRouter>,
  )
}

afterEach(async () => {
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('Breadcrumb', () => {
  it('names the group and the screen', () => {
    renderAt('/masters/origins')

    const trail = screen.getByRole('navigation', { name: 'Ruta de navegación' })
    expect(trail).toHaveTextContent('Maestros')
    expect(trail).toHaveTextContent('Orígenes')
  })

  it('marks the last entry as the current page', () => {
    renderAt('/fleet/vehicles')

    expect(screen.getByText('Vehículos').closest('li')).toHaveAttribute('aria-current', 'page')
  })

  it('shows a single entry on the home screen', () => {
    renderAt('/')

    expect(screen.getAllByRole('listitem')).toHaveLength(1)
    expect(screen.getByRole('listitem')).toHaveTextContent('Inicio')
  })

  it('resolves a nested route to the screen that owns it', () => {
    renderAt('/planning/run-42')

    expect(screen.getByRole('navigation')).toHaveTextContent('Planificación')
  })

  it('renders nothing for a path the navigation does not know', () => {
    const { container } = renderAt('/nowhere')

    expect(container).toBeEmptyDOMElement()
  })

  it('follows the active language', async () => {
    await i18n.changeLanguage('en')
    renderAt('/masters/origins')

    const trail = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(trail).toHaveTextContent('Master data')
    expect(trail).toHaveTextContent('Origins')
  })
})
