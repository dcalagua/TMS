import type {
  OwnFleetComponent, OwnFleetCostProfileView, OwnFleetCostReason, OwnFleetProfileState,
  OwnFleetQuantitySource, OwnFleetQuoteLine, OwnFleetQuoteUnavailable, OwnFleetQuoteView,
} from '../../shared/api/ownFleetCostingApi'

/**
 * Las frases que hacen honesta la pantalla de costo de flota propia (V48, JOB 22).
 *
 * Separadas de los componentes por la misma razón que en JOB 14: **la regla que importa aquí es una
 * frase**, no un layout, y una frase se puede probar sin montar React. Lo que estas funciones
 * protegen es que la pantalla nunca diga "0" donde el backend dijo "no lo sé".
 */

export function componentLabel(component: OwnFleetComponent): string {
  switch (component) {
    case 'FIXED_TRIP': return 'Cargo fijo por viaje'
    case 'FUEL_PER_KM': return 'Combustible'
    case 'DRIVER_PER_HOUR': return 'Conductor'
    case 'VEHICLE_PER_HOUR': return 'Vehículo'
    case 'MAINTENANCE_PER_KM': return 'Mantenimiento'
    case 'DEPRECIATION_PER_KM': return 'Depreciación'
    case 'TOLL': return 'Peajes'
  }
}

/** De dónde salió la cantidad. Un total sin procedencia es un número que nadie puede revisar. */
export function sourceLabel(source: OwnFleetQuantitySource | null): string | null {
  switch (source) {
    case 'MEASURED_ROUTE': return 'ruta medida'
    case 'STRAIGHT_LINE_ESTIMATE': return 'estimación en línea recta'
    case 'TRIP_EXECUTION_WINDOW': return 'ventana del viaje'
    case 'RESOURCE_DUTY_WINDOW': return 'jornada del recurso, reposicionamiento incluido'
    case 'PROFILE_FLAT': return 'tarifa fija del perfil'
    default: return null
  }
}

export function blockingReasonText(reason: OwnFleetCostReason): string {
  switch (reason) {
    case 'DISTANCE_UNKNOWN':
      return 'No se pudo medir la distancia de este viaje. Ponle coordenadas a sus paradas.'
    case 'DUTY_UNKNOWN':
      return 'Este viaje no tiene una ventana planificada, así que no se sabe cuánto ocupa al recurso.'
  }
}

export function unavailableText(reason: OwnFleetQuoteUnavailable): string {
  switch (reason) {
    case 'NO_VEHICLE_ASSIGNED':
      return 'Todavía no hay vehículo asignado, así que no hay camión cuyo costo modelar.'
    case 'NOT_OWN_FLEET':
      return 'Este envío va con un transportista: tiene un PRECIO comercial, no un costo interno nuestro.'
    case 'NO_PROFILE_IN_FORCE':
      return 'Nadie ha configurado qué cuesta operar este vehículo. Sin eso no hay costo — '
        + 'y no, eso no significa que sea gratis.'
  }
}

/**
 * El total, o la frase que dice por qué no lo hay.
 *
 * La regla central de todo el JOB 22 vive en esta función: cuando `comparableTotal` es null, la
 * pantalla **dice que no hay total**. Nunca imprime el subtotal parcial en su lugar, porque un
 * plan al que le faltan costos no debe parecer más barato que uno que sí los tiene.
 */
export function totalText(quote: OwnFleetQuoteView): string {
  if (quote.unavailableReason) {
    return 'Sin costo estimado'
  }
  if (quote.comparableTotal === null) {
    return 'Total no disponible'
  }
  return `${quote.currency ?? ''} ${quote.comparableTotal.toFixed(2)}`.trim()
}

/** La advertencia bajo el total. Null cuando el total es completo y no hay nada que aclarar. */
export function totalCaveat(quote: OwnFleetQuoteView): string | null {
  if (quote.unavailableReason) {
    return unavailableText(quote.unavailableReason)
  }
  if (quote.comparableTotal !== null) {
    return null
  }
  const missing = quote.blockingReasons.map(blockingReasonText).join(' ')
  const subtotal = quote.partialSubtotal === null
    ? ''
    : ` Lo que sí se pudo calcular suma ${quote.currency ?? ''} ${quote.partialSubtotal.toFixed(2)}, `
      + 'y ese número sirve para diagnosticar, no para decidir.'
  return `${missing}${subtotal}`
}

/** `120 km × 0.65` o `3.5 h × 18.00`, y null para un cargo fijo o una línea sin cantidad. */
export function quantityText(line: OwnFleetQuoteLine): string | null {
  if (line.quantity === null || line.rate === null || line.unit === null) {
    return null
  }
  const unit = line.unit === 'KM' ? 'km' : 'h'
  return `${line.quantity} ${unit} × ${line.rate}`
}

/**
 * Lo que muestra la celda de importe de una línea que no se pudo calcular.
 *
 * Devuelve un guion y **no `0.00`**, aunque el backend mande `amount: 0`. Ese cero existe para que
 * sumar las líneas sea una suma corriente; imprimirlo diría que combustible costó cero, que es
 * exactamente lo contrario de lo que pasó.
 */
export function amountText(line: OwnFleetQuoteLine, currency: string | null): string {
  if (line.status === 'NOT_CALCULABLE') {
    return '—'
  }
  return `${currency ?? ''} ${line.amount.toFixed(2)}`.trim()
}

export function stateLabel(state: OwnFleetProfileState): string {
  switch (state) {
    case 'ACTIVE': return 'Vigente'
    case 'INCOMPLETE': return 'Incompleto'
    case 'EXPIRED': return 'Vencido'
    case 'FUTURE': return 'Futuro'
    case 'INACTIVE': return 'Desactivado'
  }
}

export function stateColor(state: OwnFleetProfileState): 'success' | 'warning' | 'default' | 'info' {
  switch (state) {
    case 'ACTIVE': return 'success'
    case 'INCOMPLETE': return 'warning'
    case 'FUTURE': return 'info'
    default: return 'default'
  }
}

/** A qué se aplica el perfil, con el vehículo específico ganándole a su tipo. */
export function scopeText(profile: OwnFleetCostProfileView): string {
  if (profile.vehicleId) {
    return profile.vehicleLabel ?? 'Vehículo'
  }
  return `Tipo: ${profile.vehicleTypeLabel ?? 'sin nombre'}`
}

/** Qué necesita este perfil de cada viaje antes de poder dar un total comparable. */
export function requirementText(profile: OwnFleetCostProfileView): string {
  const needs: string[] = []
  if (profile.needsDistance) needs.push('distancia')
  if (profile.needsDuty) needs.push('duración')
  if (needs.length === 0) {
    return 'Cobra sólo cargos fijos, así que ningún viaje puede quedarse sin total.'
  }
  return `Necesita ${needs.join(' y ')} de cada viaje. Sin eso no hay total comparable.`
}
