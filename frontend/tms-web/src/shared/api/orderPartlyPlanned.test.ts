import { describe, expect, it } from 'vitest'
import { isPartlyPlanned } from './ordersApi'

describe('isPartlyPlanned', () => {
  it('flags a released order with part of it on a trip', () => {
    expect(isPartlyPlanned({ status: 'READY_FOR_PLANNING', allocatedPallets: 60 })).toBe(true)
  })
  it('does not flag a released order nothing of which is planned, nor an older backend', () => {
    expect(isPartlyPlanned({ status: 'READY_FOR_PLANNING', allocatedPallets: 0 })).toBe(false)
    expect(isPartlyPlanned({ status: 'READY_FOR_PLANNING' })).toBe(false)
  })
  it('does not flag other statuses', () => {
    expect(isPartlyPlanned({ status: 'PLANNED', allocatedPallets: 100 })).toBe(false)
  })
})
