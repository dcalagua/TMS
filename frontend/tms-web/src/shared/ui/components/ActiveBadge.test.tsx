import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import i18n from '../../i18n'
import { DEFAULT_LANGUAGE } from '../../i18n/config'
import { ActiveBadge } from './ActiveBadge'

afterEach(async () => {
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('ActiveBadge', () => {
  it('renders the translated status rather than a raw flag', () => {
    render(<ActiveBadge active />)

    expect(screen.getByText('Activo')).toBeInTheDocument()
  })

  it('renders the inactive status', () => {
    render(<ActiveBadge active={false} />)

    expect(screen.getByText('Inactivo')).toBeInTheDocument()
  })

  it('follows a language switch', async () => {
    await i18n.changeLanguage('en')

    render(<ActiveBadge active />)

    expect(screen.getByText('Active')).toBeInTheDocument()
  })

  it('conveys the status with text, not colour alone', () => {
    const { container } = render(<ActiveBadge active={false} />)

    // The badge class carries the colour; the accessible name must still be readable.
    expect(container.textContent?.trim()).toBe('Inactivo')
  })
})
