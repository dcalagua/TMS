/**
 * Dónde estaba el teléfono al pulsar «Llegué» (V75), o `null` si no se sabe.
 *
 * Nunca falla y nunca espera de más: sin permiso, sin GPS, sin señal o pasado el plazo, la llegada
 * se registra igual y sin posición. La posición sólo informa a quien lee la historia del viaje en
 * la oficina ("a 2 km del destino"); no confirma ni rechaza nada (ADR-007).
 *
 * El plazo propio cubre lo que `timeout` no cubre: el navegador no empieza a contar mientras su
 * diálogo de permiso sigue abierto, y un conductor que no lo contesta no puede quedarse sin llegada.
 */
export interface ArrivalPosition {
  latitude: number
  longitude: number
}

/** Lo que el conductor está dispuesto a esperar tras pulsar el botón. */
const WAIT_MS = 6_000

export function arrivalPosition(): Promise<ArrivalPosition | null> {
  if (typeof navigator === 'undefined' || !navigator.geolocation) return Promise.resolve(null)
  return new Promise((resolve) => {
    let settled = false
    const finish = (value: ArrivalPosition | null) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve(value)
    }
    const timer = setTimeout(() => finish(null), WAIT_MS)
    try {
      navigator.geolocation.getCurrentPosition(
        (position) => finish({
          // Seis decimales: lo que guarda la columna (unos 10 cm), más fino que cualquier GPS de teléfono.
          latitude: Number(position.coords.latitude.toFixed(6)),
          longitude: Number(position.coords.longitude.toFixed(6)),
        }),
        () => finish(null),
        // Una lectura del último minuto vale: el conductor no se ha movido mientras estacionaba.
        { enableHighAccuracy: true, timeout: WAIT_MS - 500, maximumAge: 60_000 },
      )
    } catch {
      finish(null)
    }
  })
}
