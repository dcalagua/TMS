# Pedidos de transporte

Contrato de dominio. Migraciones: `V10__orders.sql`, `V17__orders_declared_totals.sql`; sus
referencias apuntan a `tms.location` desde `V23`.
Detalle previo: `docs/domain/ORDER_LIFECYCLE_V1.md`, `docs/domain/ORDER_TOTALS_V1.md`.

---

## 1. Qué es

Un `TransportOrder` es la **entrada** de la planificación: algo que hay que mover desde un lugar
hasta otro en una fecha. Es el equivalente conceptual del *Order Release* de OTM.

```mermaid
flowchart LR
    O["TransportOrder"] --> P["PlanningRun"] --> T["Trip / Shipment"]
```

---

## 2. Cabecera

```text
orderNumber                      correlativo global, identidad legible
externalSource / externalReference   identidad de idempotencia por compañía
originLocation                   tms.location con rol ORIGIN
destinationLocation              tms.location con rol DESTINATION
customerName / customerReference
serviceDate                      la fecha pedida
requestedWindowStart / End       ventana horaria, opcional
priority                         LOW | NORMAL | HIGH | URGENT
status                           ver abajo
version                          bloqueo optimista
```

Los campos JSON siguen llamándose `originId` / `destinationId`. Nombran los dos extremos de un
movimiento; renombrarlos rompería el contrato de integración v1 que ya consumen sistemas externos
a cambio de un sinónimo (`docs/domain/LOCATIONS.md` §6).

Desde `V23` **ambos pueden ser la misma ubicación**: un centro de distribución que despacha y
recibe es un solo registro, y ninguna restricción prohíbe que un movimiento empiece y termine en
él.

---

## 3. Líneas y totales

```text
TransportOrderLine
  materialCode, materialDescription
  quantity, uom
  weightKg, volumeM3, pallets
```

Los totales del pedido se **calculan desde las líneas** cuando las líneas los llevan. Cuando el
origen del pedido sólo declara totales — una integración que no manda detalle, una importación
masiva — se persisten como *declarados* y `TotalsSource` registra cuál de los dos se está
mirando. Un total sin procedencia es un número que nadie puede auditar.

Ver `docs/domain/ORDER_TOTALS_V1.md` para la precedencia completa.

---

## 4. Estados

```text
NOT_READY ──► READY_FOR_PLANNING ──► PLANNED
     │                │                 │
     └────────────────┴─────► CANCELLED ┘
```

Cuatro estados, y por ahora suficientes. `PLANNED` lo escribe planificación al abrir una
asignación y se revierte al cerrarla — las dos transiciones que planning posee, y las únicas que
`OrderPlanningPort` expone. Ampliar el vocabulario (`IN_TRANSIT`, `DELIVERED`) es trabajo de
ejecución, que no existe todavía.

---

## 5. Validaciones

```text
origen        ubicación de la compañía, activa, con rol ORIGIN
destino       ubicación de la compañía, activa, con rol DESTINATION
cantidades    weight >= 0, volume >= 0, pallets >= 0
ventana       si hay inicio y fin, fin no antes que inicio
```

Se aplican en cada asignación, también al actualizar: un pedido no puede re-guardarse apuntando a
un sitio que el operador retiró de servicio.

### El calendario es una regla de planificación, no de creación

Un cliente puede pedir un martes para una tienda que se atiende lunes/miércoles/viernes. Rechazar
ese pedido sería rechazar el negocio. Lo que no debe ocurrir es que termine silenciosamente en un
camión del martes — por eso el calendario se consulta al **planificar**, a través de
`ServiceCalendarPort`, y un pedido cuyo destino no se atiende ese día aparece en la lista de no
planificados con el motivo `NOT_SERVICEABLE_ON_DATE`.

Separar las dos preguntas es lo que permite que ambas tengan la respuesta correcta.

---

## 6. Entradas

| Vía | Identidad | Documento |
|---|---|---|
| UI (`TmsDrawer`) | `orderNumber` generado | — |
| Import Center | `externalReference` por archivo | `docs/domain/IMPORT_FLOW_V1.md` |
| API inbound M2M | `(company, externalSource, externalReference)` | `docs/integrations/INBOUND_API_V1.md` |

Las tres desembocan en el mismo caso de uso. Un reintento de red no duplica: el upsert resuelve
por la pareja externa y actualiza.

---

## 7. UX

**Lista** (`/orders`): búsqueda, origen, destino, fecha, estado, prioridad; paginación de
servidor. Columnas: pedido, origen, destino, fecha, prioridad, peso, volumen, pallets, estado.

Los selectores de origen y destino son búsquedas asíncronas contra
`/masterdata/locations?role=…`, no listas completas: una compañía con 3.000 tiendas no cabe en un
desplegable.

**Drawer**:

```text
CABECERA              número, referencia externa, cliente, prioridad
ORIGEN / DESTINO      selectores filtrados por uso operacional
VENTANA DE SERVICIO   fecha y ventana horaria
CAPACIDAD             peso, volumen, pallets (calculados o declarados)
LÍNEAS                material, cantidad, unidad, peso, volumen, pallets
```

**Nunca se cargan líneas en una lista.** Un tablero resume miles de pedidos al día y no debe
traer lo que no dibuja.

---

## 8. Multitenancy

Company-scoped, con FK compuesta `(referencia_id, company_id)` hacia `tms.location` en ambos
extremos y RLS por compañía. `transport_order_line` es hijo puro de su pedido.
