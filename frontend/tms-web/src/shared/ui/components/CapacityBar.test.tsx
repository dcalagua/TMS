import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { CapacityDimension } from '../../api/planningApi'
import { CapacityBar } from './CapacityBar'

function dimension(overrides: Partial<CapacityDimension>): CapacityDimension {
  return { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: false, ...overrides }
}

describe('CapacityBar', () => {
  it('renders an unlimited dimension as unlimited, never inventing a percentage', () => {
    render(<CapacityBar label="Weight" unit="kg" dimension={dimension({ used: 250, unlimited: true })} />)

    expect(screen.getByText('250 kg · unlimited')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: /unlimited/ })).toBeInTheDocument()
  })

  it('renders a real zero limit as n/a, not 0% or 100%', () => {
    render(
      <CapacityBar
        label="Pallets"
        unit="plt"
        dimension={dimension({ used: 0, limit: 0, percentUsed: null, exceeded: false })}
      />,
    )

    expect(screen.getByText('0 plt / 0 plt · n/a')).toBeInTheDocument()
    expect(screen.queryByText(/over capacity/i)).not.toBeInTheDocument()
  })

  it('flags an exceeded zero limit as over capacity', () => {
    render(
      <CapacityBar
        label="Pallets"
        unit="plt"
        dimension={dimension({ used: 4, limit: 0, percentUsed: null, exceeded: true })}
      />,
    )

    expect(screen.getByText('Over capacity: this dimension has no room at all.')).toBeInTheDocument()
  })

  it('renders a normal within-capacity dimension with its backend-computed percentage', () => {
    render(
      <CapacityBar
        label="Volume"
        unit="m³"
        dimension={dimension({ used: 12, limit: 20, percentUsed: 60, exceeded: false })}
      />,
    )

    expect(screen.getByText('12 m³ / 20 m³ (60%)')).toBeInTheDocument()
    expect(screen.queryByText('Over capacity.')).not.toBeInTheDocument()
  })

  it('flags an exceeded dimension as over capacity in red', () => {
    render(
      <CapacityBar
        label="Weight"
        unit="kg"
        dimension={dimension({ used: 1200, limit: 1000, percentUsed: 120, exceeded: true })}
      />,
    )

    expect(screen.getByText('1200 kg / 1000 kg (120%)')).toBeInTheDocument()
    expect(screen.getByText('Over capacity.')).toBeInTheDocument()
  })
})
