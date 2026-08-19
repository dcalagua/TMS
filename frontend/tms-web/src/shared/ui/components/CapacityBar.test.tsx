import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { CapacityDimension } from '../../api/planningApi'
import i18n from '../../i18n'
import { DEFAULT_LANGUAGE } from '../../i18n/config'
import { CapacityBar } from './CapacityBar'

function dimension(overrides: Partial<CapacityDimension>): CapacityDimension {
  return { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: false, ...overrides }
}

afterEach(async () => {
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('CapacityBar', () => {
  it('renders an unlimited dimension as unlimited, never inventing a percentage', () => {
    render(<CapacityBar kind="weight" dimension={dimension({ used: 250, unlimited: true })} />)

    expect(screen.getByText(/250 kg · Sin límite/)).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: /sin límite/i })).toBeInTheDocument()
  })

  it('renders a real zero limit as "no capacity", not 0% or 100%', () => {
    render(<CapacityBar kind="pallets" dimension={dimension({ used: 0, limit: 0, percentUsed: null, exceeded: false })} />)

    expect(screen.getByText(/0 plt \/ 0 plt · sin capacidad/)).toBeInTheDocument()
    expect(screen.queryByText(/Excede la capacidad/i)).not.toBeInTheDocument()
  })

  it('flags an exceeded zero limit as having no capacity at all', () => {
    render(<CapacityBar kind="pallets" dimension={dimension({ used: 4, limit: 0, percentUsed: null, exceeded: true })} />)

    expect(screen.getByText('Esta dimensión no tiene capacidad disponible.')).toBeInTheDocument()
  })

  it('shows used, limit and percentage together for a normal dimension', () => {
    render(<CapacityBar kind="volume" dimension={dimension({ used: 12, limit: 20, percentUsed: 60, exceeded: false })} />)

    expect(screen.getByText('12 m³ / 20 m³')).toBeInTheDocument()
    expect(screen.getByText('(60%)')).toBeInTheDocument()
    expect(screen.queryByText('Excede la capacidad')).not.toBeInTheDocument()
    expect(screen.queryByText('Cerca del límite')).not.toBeInTheDocument()
  })

  it('exposes the backend percentage to assistive technology', () => {
    render(<CapacityBar kind="weight" dimension={dimension({ used: 8850, limit: 10000, percentUsed: 88.5 })} />)

    const bar = screen.getByRole('progressbar')
    expect(bar).toHaveAttribute('aria-valuenow', '89')
    expect(bar).toHaveAttribute('aria-valuemin', '0')
    expect(bar).toHaveAttribute('aria-valuemax', '100')
    expect(bar).toHaveAccessibleName(/Peso/)
  })

  it('warns near the limit in words, not by colour alone', () => {
    render(<CapacityBar kind="weight" dimension={dimension({ used: 8850, limit: 10000, percentUsed: 88.5 })} />)

    expect(screen.getByText('8,850 kg / 10,000 kg')).toBeInTheDocument()
    expect(screen.getByText('(88.5%)')).toBeInTheDocument()
    expect(screen.getByText('Cerca del límite')).toBeInTheDocument()
  })

  it('flags an exceeded dimension in words as well as colour', () => {
    render(<CapacityBar kind="weight" dimension={dimension({ used: 1200, limit: 1000, percentUsed: 120, exceeded: true })} />)

    expect(screen.getByText('1,200 kg / 1,000 kg')).toBeInTheDocument()
    expect(screen.getByText('Excede la capacidad')).toBeInTheDocument()
  })

  it('never reports a verdict the backend did not give', () => {
    // 120% used but `exceeded` false: the backend is the authority, so no over-capacity text.
    render(<CapacityBar kind="weight" dimension={dimension({ used: 1200, limit: 1000, percentUsed: 120, exceeded: false })} />)

    expect(screen.queryByText('Excede la capacidad')).not.toBeInTheDocument()
    expect(screen.getByText('Cerca del límite')).toBeInTheDocument()
  })

  it('follows a language switch', async () => {
    await i18n.changeLanguage('en')

    render(<CapacityBar kind="weight" dimension={dimension({ used: 1200, limit: 1000, percentUsed: 120, exceeded: true })} />)

    expect(screen.getByText('Over capacity')).toBeInTheDocument()
  })
})
