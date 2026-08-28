import { describe, expect, it } from 'vitest'
import type { OwnFleetQuoteLine, OwnFleetQuoteView } from './ownFleetCostingApi'
import {
  amountText, quantityText, requirementText, totalCaveat, totalText,
} from '../../pages/costing/ownFleetCostText'

/**
 * Las frases que impiden que la pantalla mienta sobre el costo de la flota propia (V48, JOB 22).
 *
 * Todas prueban la misma regla desde ángulos distintos: **lo que no se sabe nunca se imprime como
 * cero**. Es la regla que ya defendieron V43 (ETAs), V45 (cantidades) y V47 (reposicionamiento), y
 * aquí es la que evita que un plan parezca barato por no poder costear sus propios costos.
 */

function quote(overrides: Partial<OwnFleetQuoteView>): OwnFleetQuoteView {
  return {
    tripId: 't-1',
    nature: 'OWN_FLEET_INTERNAL_COST',
    currency: 'PEN',
    comparableTotal: null,
    partialSubtotal: null,
    complete: false,
    profileId: 'p-1',
    profileScope: 'VEHICLE',
    blockingReasons: [],
    unavailableReason: null,
    lines: [],
    ...overrides,
  }
}

function line(overrides: Partial<OwnFleetQuoteLine>): OwnFleetQuoteLine {
  return {
    component: 'FUEL_PER_KM',
    status: 'APPLIED',
    rate: 0.65,
    quantity: 120,
    unit: 'KM',
    quantitySource: 'MEASURED_ROUTE',
    amount: 78,
    reason: null,
    ...overrides,
  }
}

describe('el total de flota propia', () => {
  it('muestra la cifra cuando está completa', () => {
    expect(totalText(quote({ comparableTotal: 316.6, complete: true }))).toBe('PEN 316.60')
    expect(totalCaveat(quote({ comparableTotal: 316.6, complete: true }))).toBeNull()
  })

  it('dice que no hay total cuando falta un insumo, y NO imprime el subtotal en su lugar', () => {
    const incomplete = quote({
      comparableTotal: null,
      partialSubtotal: 205,
      blockingReasons: ['DISTANCE_UNKNOWN'],
    })

    expect(totalText(incomplete)).toBe('Total no disponible')
    // La cifra parcial aparece, y aparece dicha como lo que es. Si `totalText` devolviera
    // "PEN 205.00" este viaje le ganaría a uno de 316.60 que sí se pudo medir entero.
    expect(totalText(incomplete)).not.toContain('205')
    expect(totalCaveat(incomplete)).toContain('205.00')
    expect(totalCaveat(incomplete)).toContain('no para decidir')
  })

  it('explica que un camión sin tarifas configuradas no es un camión gratis', () => {
    const unconfigured = quote({ unavailableReason: 'NO_PROFILE_IN_FORCE', currency: null })

    expect(totalText(unconfigured)).toBe('Sin costo estimado')
    expect(totalCaveat(unconfigured)).toContain('no significa que sea gratis')
  })

  it('distingue un envío subcontratado, que tiene precio y no costo interno', () => {
    expect(totalCaveat(quote({ unavailableReason: 'NOT_OWN_FLEET' })))
      .toContain('PRECIO comercial')
  })
})

describe('las líneas del desglose', () => {
  it('conservan cantidad, tarifa e importe', () => {
    expect(quantityText(line({}))).toBe('120 km × 0.65')
    expect(amountText(line({}), 'PEN')).toBe('PEN 78.00')
  })

  it('muestran un guion, no 0.00, en una línea que no se pudo calcular', () => {
    const unknown = line({ status: 'NOT_CALCULABLE', quantity: null, amount: 0, reason: 'DISTANCE_UNKNOWN' })

    // El backend manda `amount: 0` para que sumar las líneas sea una suma corriente. Imprimirlo
    // diría que el combustible costó cero, que es justo lo contrario de lo que pasó.
    expect(amountText(unknown, 'PEN')).toBe('—')
    expect(quantityText(unknown)).toBeNull()
  })

  it('mantienen la tarifa aunque no haya cantidad, porque no es lo mismo que no cobrarla', () => {
    const unknown = line({ status: 'NOT_CALCULABLE', quantity: null, amount: 0 })

    expect(unknown.rate).toBe(0.65)
  })
})

describe('lo que un perfil exige de cada viaje', () => {
  const profile = {
    id: 'p', vehicleId: 'v', vehicleLabel: 'VEH-1', vehicleTypeId: null, vehicleTypeLabel: null,
    currency: 'PEN', effectiveFrom: '2026-01-01', effectiveTo: null, active: true,
    state: 'ACTIVE' as const, fixedTripAmount: 100, fuelPerKm: null, driverPerHour: null,
    vehiclePerHour: null, maintenancePerKm: null, depreciationPerKm: null, tollAmount: null,
    needsDistance: false, needsDuty: false, notes: null,
  }

  it('un perfil sólo de cargos fijos no puede dejar ningún viaje sin total', () => {
    expect(requirementText(profile)).toContain('ningún viaje puede quedarse sin total')
  })

  it('un perfil por kilómetro y por hora dice que necesita ambas cosas', () => {
    expect(requirementText({ ...profile, needsDistance: true, needsDuty: true }))
      .toContain('distancia y duración')
  })
})
