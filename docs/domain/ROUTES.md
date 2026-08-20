# Rutas maestras

Contrato de dominio. Migración: `V8__masterdata_routes.sql`; sus referencias apuntan a
`tms.location` desde `V23`.

---

## 1. Una ruta maestra no es una ruta calculada

```text
MASTER ROUTE            ≠        SHIPMENT / TRIP ROUTE
plantilla                        resultado de planificar
corredor habitual                paradas reales de un viaje
la edita un maestro              la construyen las asignaciones
estable                          cambia con cada plan
```

Una ruta maestra es una **preferencia**: "este corredor normalmente se sirve en este orden".
Sirve como plantilla, como agrupador operacional y como entrada para planificación. No
representa una optimización ejecutada y nada en `planning` construye una parada a partir de
ella: `tms.trip_stop` sigue siempre las asignaciones activas del viaje.

Un viaje puede *sugerirse* desde una ruta, y no queda atado a ella: editar el maestro mañana no
reescribe un shipment de ayer.

---

## 2. Modelo

```text
Route
  code                 único por compañía
  name
  originLocation       tms.location con rol ORIGIN
  zone                 opcional
  frequency            opcional — la cadencia habitual del corredor
  referenceDistanceKm  dato del planificador, no medido
  referenceDurationMin dato del planificador, no medido
  active

RouteStop
  location             tms.location con rol DESTINATION
  sequence             1..N, contiguo
```

`referenceDistanceKm` y `referenceDurationMinutes` son **pistas capturadas a mano**. No se copian
a un shipment: publicar una cifra que nadie midió como si fuera del viaje sería inventar datos.

`RouteStop` no tiene `serviceTimeOverride`. El tiempo de atención vive en la ubicación
(`location.service_time_minutes`), que es donde un operador espera encontrarlo; una excepción por
ruta es una columna nueva el día que haya un corredor que realmente la necesite.

---

## 3. Validación

Al crear o actualizar, todas se comprueban — también al actualizar:

```text
originLocation      existe, de la compañía, activa, con rol ORIGIN
cada parada         existe, de la compañía, activa, con rol DESTINATION
zona / frecuencia   si se indican, de la compañía
paradas             sin duplicados; una ubicación aparece como máximo una vez por ruta
```

`uq_route_stop_route_destination` lo garantiza también en base de datos. Una ruta maestra es un
corredor de paradas distintas; visitar dos veces el mismo sitio es un itinerario de viaje, no una
plantilla.

El error de una parada inválida **nombra el id ofensor**: una ruta puede tener docenas de
paradas y "corrige la que está mal" no es una instrucción que nadie pueda seguir.

### Mostrar frente a asignar

Renderizar una ruta guardada es deliberadamente más laxo que editarla: una parada cuya ubicación
fue desactivada después sigue mostrándose. La ruta tiene que seguir diciendo a dónde va.

---

## 4. UX

**Lista** (`/masters/routes`): código, nombre, origen, zona, frecuencia, número de paradas,
estado. El conteo viene de un `GROUP BY` por página, nunca de cargar las paradas de cada fila.

**Drawer**:

```text
IDENTIFICACIÓN     código, nombre, origen, zona, frecuencia
PARADAS            1 Tienda A · 2 Tienda B · 3 Tienda C
                   subir / bajar / eliminar / agregar
REFERENCIA         distancia, duración
ESTADO             activo
```

Botones arriba/abajo en lugar de arrastrar y soltar: más robustos, accesibles por teclado sin
trabajo extra, y comprensibles en móvil. Drag & drop puede añadirse encima más adelante; no puede
reemplazarlos.

**Mapa**: si Google Maps está configurado, la ruta puede previsualizarse con sus paradas
numeradas. Sin clave, el CRUD funciona igual — Maps es UX, nunca un bloqueo
(`docs/integrations/GOOGLE_MAPS.md`).

---

## 5. Multitenancy

Company-scoped. Cada referencia lleva FK compuesta `(referencia_id, company_id)`, de modo que una
ruta de la compañía A no puede apuntar a una ubicación, zona o frecuencia de la B ni por error de
servicio ni por escritura directa. RLS por compañía sobre `route` y, a través del padre, sobre
`route_stop`.
