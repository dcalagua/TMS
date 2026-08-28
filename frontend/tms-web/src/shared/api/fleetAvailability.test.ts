import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { COMPANY_ID_HEADER, setAuthRefreshHandler, setAuthTokenProvider } from './httpClient'
import {
  DRIVER_UNAVAILABILITY_REASONS, VEHICLE_UNAVAILABILITY_REASONS,
  blockDriver, blockVehicle, listDriverShifts, listVehicleUnavailability, releaseVehicle,
  setDriverShift,
} from './fleetAvailabilityApi'
import { enumLabel } from '../../lib/enums'

const COMPANY = '11111111-1111-4111-8111-111111111111'
const VEHICLE = '22222222-2222-4222-8222-222222222222'
const DRIVER = '33333333-3333-4333-8333-333333333333'

let fetchMock: ReturnType<typeof vi.fn>

function sent() {
  const [url, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit]
  return { url: new URL(url), init, headers: (init.headers ?? {}) as Record<string, string> }
}

beforeEach(() => {
  // Una fábrica, no un valor: el cuerpo de un Response sólo puede leerse una vez.
  fetchMock = vi.fn().mockImplementation(async () => new Response(JSON.stringify([]), {
    status: 200, headers: { 'content-type': 'application/json' },
  }))
  vi.stubGlobal('fetch', fetchMock)
  setAuthTokenProvider(async () => 'test-token')
  setAuthRefreshHandler(async () => null)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/**
 * Las rutas literales, que es la parte del contrato que ningún typechecker ve — el mismo motivo por
 * el que existe `criticalEndpoints.test.ts`. Renombrar una ruta en el backend compila perfectamente
 * y se manifiesta como un cajón vacío.
 *
 * Importa además **cuál** de las dos rutas se llama: el backend guarda las bajas de vehículo detrás
 * de `fleet.vehicle:manage` y las de conductor detrás de `fleet.driver:manage`, precisamente para
 * que quien mete camiones al taller no vea quién está de baja médica. Llamar a la ruta equivocada
 * pediría el permiso equivocado.
 */
describe('rutas de disponibilidad de flota', () => {
  it('las ventanas de un vehículo van a su propia ruta, con el ámbito de empresa', async () => {
    await listVehicleUnavailability(COMPANY, VEHICLE)

    const { url, headers } = sent()
    expect(url.pathname).toBe(`/api/v1/fleet/vehicles/${VEHICLE}/unavailability`)
    expect(headers[COMPANY_ID_HEADER]).toBe(COMPANY)
  })

  it('bloquear un vehículo es un POST a la ruta del vehículo', async () => {
    await blockVehicle(COMPANY, VEHICLE, {
      reason: 'MAINTENANCE', startsAt: '2026-09-07T08:00:00Z', endsAt: '2026-09-07T12:00:00Z',
    })

    const { url, init } = sent()
    expect(init.method).toBe('POST')
    expect(url.pathname).toBe(`/api/v1/fleet/vehicles/${VEHICLE}/unavailability`)
  })

  it('bloquear un conductor es otra ruta, no la del vehículo', async () => {
    await blockDriver(COMPANY, DRIVER, {
      reason: 'MEDICAL', startsAt: '2026-09-07T00:00:00Z', endsAt: '2026-09-08T00:00:00Z',
    })

    const { url } = sent()
    expect(url.pathname).toBe(`/api/v1/fleet/drivers/${DRIVER}/unavailability`)
    expect(url.pathname).not.toContain('vehicles')
  })

  it('liberar es un DELETE que nombra el recurso además de la ventana', async () => {
    await releaseVehicle(COMPANY, VEHICLE, 'block-1')

    const { url, init } = sent()
    expect(init.method).toBe('DELETE')
    // El recurso va en la ruta y no sólo el id de la ventana: el backend comprueba que la ventana
    // sea de este vehículo, y una ruta que no lo nombrara dejaría sin efecto esa comprobación.
    expect(url.pathname).toBe(`/api/v1/fleet/vehicles/${VEHICLE}/unavailability/block-1`)
  })

  it('los turnos se leen y se fijan sobre el conductor', async () => {
    await listDriverShifts(COMPANY, DRIVER)
    expect(sent().url.pathname).toBe(`/api/v1/fleet/drivers/${DRIVER}/shifts`)

    // PUT y no POST: hay una fila por conductor y día, así que fijar el martes dos veces es un
    // turno y no dos, repita lo que repita quien llama.
    await setDriverShift(COMPANY, DRIVER, { dayOfWeek: 'TUESDAY', startsAt: '06:00', endsAt: '16:00' })
    expect(sent().init.method).toBe('PUT')
  })
})

/**
 * Los motivos de indisponibilidad (migración V42).
 *
 * El backend rechaza un camión de vacaciones y un conductor en reparación: cada motivo declara qué
 * recurso describe. El formulario ofrece dos listas distintas por eso, y estas pruebas fijan la
 * separación — si alguien añade un motivo a la lista equivocada, el usuario elige una opción que el
 * servidor va a rechazar, que es exactamente la clase de error que un desplegable no debería
 * permitir.
 */
describe('motivos de indisponibilidad', () => {
  it('un vehículo no puede estar de vacaciones ni de baja médica', () => {
    expect(VEHICLE_UNAVAILABILITY_REASONS).not.toContain('HOLIDAY')
    expect(VEHICLE_UNAVAILABILITY_REASONS).not.toContain('MEDICAL')
    expect(VEHICLE_UNAVAILABILITY_REASONS).not.toContain('ABSENCE')
    expect(VEHICLE_UNAVAILABILITY_REASONS).not.toContain('TRAINING')
  })

  it('un conductor no entra a taller ni a inspección', () => {
    expect(DRIVER_UNAVAILABILITY_REASONS).not.toContain('MAINTENANCE')
    expect(DRIVER_UNAVAILABILITY_REASONS).not.toContain('REPAIR')
    expect(DRIVER_UNAVAILABILITY_REASONS).not.toContain('INSPECTION')
  })

  it('OTHER sirve para los dos: una operación siempre tiene un motivo que nadie listó', () => {
    expect(VEHICLE_UNAVAILABILITY_REASONS).toContain('OTHER')
    expect(DRIVER_UNAVAILABILITY_REASONS).toContain('OTHER')
  })

  it('todos los motivos tienen etiqueta traducida, ninguno cae al valor crudo', () => {
    const all = [...VEHICLE_UNAVAILABILITY_REASONS, ...DRIVER_UNAVAILABILITY_REASONS]
    for (const reason of all) {
      const label = enumLabel('unavailabilityReason', reason)
      expect(label).not.toEqual(reason)
      expect(label.trim()).not.toEqual('')
    }
  })
})
