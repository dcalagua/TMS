# Frecuencias y calendarios de servicio

Contrato de dominio: qué días puede atenderse una ubicación, con qué corte y con cuánta
anticipación, y qué pasa en las fechas que se apartan de esa cadencia.

Migraciones: `V7__masterdata_destinations_frequencies.sql` (frecuencia, reglas semanales,
excepciones) y `V15__masterdata_location_frequency.sql` (la asociación con la ubicación).

---

## 1. El modelo, y por qué tiene tres piezas

```mermaid
flowchart TD
    L["Location<br/>Tienda Miraflores"] --> LF["LocationFrequency<br/>vigencia desde/hasta, activa"]
    LF --> F["Frequency<br/>LUN-MIE-VIE"]
    F --> W["FrequencyWeeklyRule × 7<br/>día, activo, corte, anticipación"]
    F --> E["FrequencyException<br/>fecha, abierto/cerrado, nota"]
```

La pieza que suele sorprender es `LocationFrequency`. Una ubicación **no** apunta a una
frecuencia con `location.frequency_id`, y eso es deliberado: una tienda puede recibir mercadería
lunes/miércoles/viernes y despachar devoluciones martes/jueves, y una columna sola cierra esa
puerta. La asociación es una entidad propia con vigencia (`effective_from` / `effective_to`) y
estado, de modo que un cambio de calendario en octubre no reescribe lo que regía en septiembre.

Hoy el producto no distingue "recepción" de "recojo" — no hay tipo de asociación. La estructura
admite varias asociaciones por ubicación y la evaluación las considera todas; etiquetar cada una
con su propósito es una columna nueva el día que exista el requisito, no antes.

---

## 2. Frequency

```text
code            único por compañía, normalizado
name
description
weeklyRules     siempre 7, una por día (lunes = 1 … domingo = 7)
active
```

Cada regla semanal lleva:

```text
dayOfWeek       1..7
enabled         ¿se atiende ese día?
cutoffTime      hora de corte, opcional
leadTimeDays    anticipación en días, opcional
```

Las siete filas se escriben siempre, habilitadas o no. Un calendario con "los días que faltan
significan no" es un calendario que hay que leer dos veces.

Ejemplo:

```text
Tienda Miraflores

LUN   sí     corte 15:00   anticipación 1
MAR   no
MIE   sí     corte 15:00   anticipación 1
JUE   no
VIE   sí     corte 15:00   anticipación 1
SAB   no
DOM   no
```

---

## 3. Excepciones por fecha

Una regla semanal dice "miércoles". El 25 de diciembre es miércoles y el depósito está cerrado; el
sábado anterior trabaja todo el mundo. Ninguna de las dos cosas se puede expresar editando la
cadencia — cambiarla cambiaría todas las semanas.

`frequency_exception` tiene exactamente dos formas, según `service_override`:

| Valor | Significado | UI |
|---|---|---|
| `false` | **Cerrado**: quita una fecha que la cadencia habría atendido | `Cerrado` |
| `true` | **Abierto**: agrega una fecha que la cadencia no atendería | `Abierto` |

```text
25/12   CERRADO   Navidad
19/12   ABIERTO   Refuerzo pre-navideño
```

### Lo que todavía no existe

Un **corte distinto para una fecha concreta** (`24/12 corte 11:00`) no es representable: la
excepción es booleana y no lleva hora. La ayuda del editor lo dice explícitamente, en vez de
dejar que un operador lo descubra al no encontrar el campo. Añadirlo es una columna
`cutoff_time` nullable en una migración futura más su lectura en `FrequencyCalendar`; no se
implementó aquí porque no se puede verificar contra una base de datos en este entorno.

---

## 4. La decisión de elegibilidad

`LocationEligibilityEvaluator` es una función pura — sin repositorio, sin Spring — y por eso está
probada directamente. `LocationEligibilityService` orquesta: carga las asociaciones de la
ubicación, sus frecuencias y la excepción de la fecha, y delega.

Orden de resolución para una fecha:

```text
1. ¿La ubicación está activa?              no → no elegible
2. ¿Alguna asociación activa cubre la fecha? no → no elegible
3. Para cada candidata, en orden:
     ¿hay excepción para esa fecha?  → la excepción manda (abierto/cerrado)
     si no                           → manda la regla semanal
4. La primera que atienda gana, y devuelve su corte y anticipación.
```

Una ubicación servida por dos calendarios sólo necesita que **uno** atienda la fecha.

---

## 5. Dónde se usa

| Consumidor | Cómo |
|---|---|
| Drawer de Ubicación | Panel de calendario de servicio + verificación de elegibilidad para una fecha |
| Planificación automática | `ServiceCalendarPort.serviceableOn(...)`, en lote, para filtrar el backlog del día |
| Rutas | Una ruta maestra puede referenciar una frecuencia como cadencia sugerida del corredor |

**Crear un pedido no consulta el calendario.** Un cliente puede pedir un martes para una tienda
que se atiende lunes/miércoles/viernes, y rechazar el pedido sería rechazar el negocio. Lo que no
debe ocurrir es que ese pedido termine silenciosamente en un camión del martes — por eso el
calendario es un filtro de **planificación**. Ver `docs/domain/ORDERS.md`.

Regla asociada: una ubicación **sin ningún calendario** cuenta como atendible. El operador que no
configuró un calendario no dijo "nunca".

---

## 6. Multitenancy

`frequency` y `location_frequency` son company-scoped, con FK compuesta `(id, company_id)` en
cada referencia y política RLS por compañía (ADR-005). `frequency_weekly_rule` y
`frequency_exception` son hijos puros de `frequency`: sin `company_id` propio, alcanzables
exactamente cuando su padre lo es.

---

## 7. UX

**Lista** (`/masters/frequencies`): búsqueda, estado, resumen de días.

**Drawer**:

```text
IDENTIFICACIÓN        código, nombre, descripción
CADENCIA SEMANAL      los 7 días en una grilla, con corte y anticipación por día
EXCEPCIONES           fechas cerradas/abiertas, con nota  (sólo al editar)
ESTADO                activo
```

Las excepciones son un sub-recurso con endpoints propios, así que son un panel y no campos del
formulario: cada fila es una llamada a la API y necesita una frecuencia guardada de la que
colgar. Al crear, el panel dice que hay que guardar primero — mejor que un panel que parece roto
porque cada llamada daría 404.
