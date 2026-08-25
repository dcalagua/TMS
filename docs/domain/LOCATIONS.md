# Ubicaciones, Orígenes y Destinos

Contrato de dominio. Este documento es la referencia para cualquier cambio en Location,
Location Type, Location Role, y para los módulos que los consumen.

Migraciones: `V14__masterdata_canonical_location.sql` (maestro canónico) y
`V23__location_canonical_unification.sql` (unificación). Decisión y alternativas:
`docs/architecture/ADR_LOCATION_MODEL.md`.

---

## 1. Las cuatro definiciones

```text
Location        = el lugar físico canónico. Un sitio, un registro.
Location Type   = QUÉ ES ese lugar.        Un valor por ubicación.
Operational Use = CÓMO PUEDE UTILIZARSE.   Un conjunto por ubicación (rol).
Origen          = una Location con el uso ORIGIN.
Destino         = una Location con el uso DESTINATION.
```

Origen y Destino **no son entidades**. Son la misma Location vista desde un extremo u otro de
un movimiento.

```text
                        UBICACIÓN
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
      Tienda              CD              Planta      <- TIPO (qué es)
         │                 │                 │
         └─────────────────┼─────────────────┘
                           │
                    USO OPERACIONAL
                 ┌─────────┴─────────┐
                 │                   │
              ORIGEN              DESTINO             <- ROL (cómo se usa)
```

### Por qué la distinción importa

Antes de V23 el vocabulario de roles incluía `STORE`, `DC`, `PLANT`, `HUB` y `OTHER` junto a
`ORIGIN` y `SHIP_TO`. La pantalla mostraba:

```text
Tipo:  Tienda
Roles: Tienda, Destino
```

Eso enseña al operador que el maestro se contradice, y convierte "rol" en una palabra que a
veces significa capacidad y a veces categoría. Los cinco valores de clasificación fueron
eliminados: ya viven en `location_type`, con más precisión y un solo valor.

---

## 2. El caso que define el modelo

```text
                     UBICACIÓN
                TIENDA MIRAFLORES
                        │
                    Tipo: STORE
                        │
              ┌─────────┴─────────┐
              │                   │
           ORIGEN              DESTINO
        devolución             entrega
```

Una entrega:

```text
CD Lima  ──▶  Tienda Miraflores          Miraflores actúa como DESTINO
```

Una devolución de esa misma tienda:

```text
Tienda Miraflores  ──▶  CD Lima          Miraflores actúa como ORIGEN
```

**Un solo registro.** Una dirección, un par de coordenadas, una zona, una referencia externa,
un `active`. Desactivar la tienda la retira de los dos extremos a la vez, porque es una fila y
una bandera.

El mismo razonamiento aplica al centro de distribución:

```text
                     UBICACIÓN
                   CD LIMA 001
                        │
         Tipo: DISTRIBUTION_CENTER
                        │
              ┌─────────┴─────────┐
              │                   │
           ORIGEN              DESTINO
              │                   │
              └─────────┬─────────┘
                        │
                 Transport Order
```

Nada impide que `transport_order.origin_id = transport_order.destination_id` si un movimiento
empieza y termina en el mismo sitio. Ninguna restricción lo prohíbe, y ninguna debería.

---

## 3. Tipos de ubicación

`tms.location.location_type`, un valor, validado por `ck_location_type`:

| Valor | Significado |
|---|---|
| `WAREHOUSE` | Almacén |
| `DISTRIBUTION_CENTER` | Centro de distribución |
| `PLANT` | Planta |
| `HUB` | Hub / cross-dock |
| `STORE` | Tienda |
| `BRANCH` | Sucursal |
| `CUSTOMER` | Cliente |
| `DELIVERY_POINT` | Punto de entrega |
| `OTHER` | Otro |

El tipo **no restringe** el uso operacional. Una planta normalmente solo despacha y una tienda
normalmente solo recibe, pero eso es una convención del negocio, no una regla del sistema: la
planta que recibe insumos y la tienda que despacha devoluciones son casos reales, y el modelo
los admite marcando la casilla correspondiente.

---

## 4. Usos operacionales

`tms.location_role`, uno o más por ubicación, validado por `ck_location_role_role`:

| Valor | Habilita |
|---|---|
| `ORIGIN` | origen de pedido, origen de ruta, origen de planning run |
| `DESTINATION` | destino de pedido, parada de ruta, parada de viaje |

Una ubicación sin ningún uso es representable en base de datos pero **no** por la API:
`LocationRequest.roles` es `@NotEmpty`. Una ubicación que no puede usarse para nada no es un
registro de negocio, es un error de captura.

### Extensión futura

`PICKUP`, `DELIVERY`, `CROSS_DOCK`, `RETURN_POINT` son extensiones plausibles. Cuestan una
migración que relaje `ck_location_role_role` y un valor en `LocationRole`. **No se implementan
hasta que exista un requisito funcional concreto**, según la regla de simplicidad del
repositorio.

---

## 5. Campos de Location

| Concern | Columnas |
|---|---|
| Tenant | `company_id` (NOT NULL, FK, índice líder, `UNIQUE (id, company_id)`) |
| Identidad | `code` (único por compañía, normalizado), `external_system` + `external_reference` (par único por compañía) |
| Nombre | `name` |
| Clasificación | `location_type` |
| Dirección | `address`, `address_reference` |
| Localidad | `district`, `province`, `department`, `country` |
| Zona horaria | `time_zone` (IANA, validada en el servicio) |
| Geografía | `latitude`, `longitude` + `geo_point geography(Point,4326)` GENERATED, índice GiST |
| Operación | `service_time_minutes`, `zone_id` |
| Estado | `active` |
| Auditoría | `created_at`, `updated_at`, `created_by`, `updated_by` |

Reglas invariantes en base de datos:

- coordenadas ambas o ninguna (`ck_location_coordinates_pair`), y dentro de rango;
- referencia externa sin sistema es inválida (`ck_location_external_pair_complete`);
- `zone_id` de otra compañía es imposible (FK compuesta `(zone_id, company_id)`).

`Location` **no** lleva columna `version`. Las escrituras de maestro son de baja concurrencia y
la carrera que importa - dos altas con el mismo código - la resuelve `uq_location_company_code`
como conflicto 409. Si aparece edición concurrente real, añadir `@Version` es una migración
aditiva.

---

## 6. Consumidores

Desde V23, las seis referencias apuntan a `tms.location`:

| Tabla | Columna | Regla que aplica el servicio |
|---|---|---|
| `tms.route` | `origin_id` | activa, de la compañía, con `ORIGIN` |
| `tms.route_stop` | `destination_id` | activa, de la compañía, con `DESTINATION` |
| `tms.transport_order` | `origin_id` | activa, de la compañía, con `ORIGIN` |
| `tms.transport_order` | `destination_id` | activa, de la compañía, con `DESTINATION` |
| `tms.planning_run` | `origin_id` | activa, de la compañía, con `ORIGIN` |
| `tms.trip_stop` | `destination_id` | derivada del pedido asignado |

Los nombres de columna y de campo JSON siguen siendo `originId` / `destinationId`. Nombran los
dos extremos de un movimiento, que es lo que un pedido tiene; renombrarlos a
`originLocationId` rompería el contrato de integración v1 que ya consumen sistemas externos a
cambio de un sinónimo. Los `COMMENT ON COLUMN` de V23 dejan explícito a qué apuntan.

### Asignar frente a mostrar

Es la asimetría central del módulo y está implementada en `LocationReferenceAdapter`:

- **Asignar** (`findActiveInCompany`, `findActiveByCodesInCompany`) filtra por compañía, por
  `active` y por rol. Un sitio que solo recibe no puede asignarse como origen.
- **Mostrar** (`findAllInCompany`) filtra solo por compañía. Un pedido ya apunta a donde
  apunta: si esa ubicación se desactivó o perdió un uso, el pedido tiene que seguir mostrando
  el lugar al que realmente se despachó. Ocultarlo sería reescribir la historia para que
  encaje con el maestro de hoy.

---

## 7. Tenancy y RLS

- `CompanyScope` resuelto en servidor; ningún servicio acepta un `companyId` del cliente.
- Todo finder lleva el predicado de compañía **dentro de la consulta**.
- Cada referencia lleva FK **compuesta** `(referencia_id, company_id)`, de modo que una
  referencia cruzada entre compañías es imposible a nivel de motor, no solo de servicio.
- RLS (ADR-005): política `p_tenant_company_scope` `FOR ALL` con `USING` y `WITH CHECK` sobre
  `tms.location`; `tms.location_role` se resuelve a través del padre.
- Permisos: `masterdata.location:read` y `masterdata.location:manage`. Las pantallas de
  Orígenes y Destinos usan los mismos, porque operan sobre el mismo maestro.

---

## 8. Las tres pantallas

| Pantalla | Ruta | Qué es |
|---|---|---|
| Ubicaciones | `/masters/locations` | el maestro completo |
| Orígenes | `/masters/origins` | `LocationsPage view="ORIGIN"` |
| Destinos | `/masters/destinations` | `LocationsPage view="DESTINATION"` |

Un solo componente. En una vista con `view`, el filtro de uso operacional no se ofrece - **es**
la pantalla -, el botón se llama `Nuevo origen` / `Nuevo destino`, y el drawer se abre con esa
casilla ya marcada. El título del drawer sigue diciendo `Nueva ubicación`, que es lo que
realmente se está creando: esa pequeña fricción es lo que enseña el modelo.

Tabla: Código · Nombre (con dirección) · Tipo · Uso operacional · Zona · Estado · Acciones. El
uso se muestra como badges discretos (`Origen`, `Destino`), nunca repitiendo el tipo.

---

## 9. Google Maps

`docs/integrations/GOOGLE_MAPS.md`. Location es el **único** maestro donde se captura
geolocalización.

- La clave viaja solo por variable de entorno (`VITE_GOOGLE_MAPS_API_KEY`), nunca versionada.
- Sin clave, el picker degrada a un aviso y los campos de latitud/longitud manuales siguen
  funcionando. **El CRUD nunca se bloquea por Maps.**
- Maps es UX. La fuente geográfica en base de datos es PostGIS: `geo_point` es generada por el
  motor a partir de `latitude`/`longitude`, no escrita por el cliente.

---

## 10. Integraciones

`external_system` + `external_reference`, único por compañía (`uq_location_external`), es la
identidad de idempotencia. `IntegrationLocationService` resuelve por ese par y, si no existe,
por `code`.

```text
ERP  ──▶  upsert location (SAP / STORE-000123)  ──▶  TMS
```

Reenviar el mismo payload no duplica: actualiza. Una referencia sin sistema se rechaza, porque
no identifica nada.

---

## 11. Importación masiva

Import Center (`/masterdata/locations/import`): plantilla, `dry-run` con preview y aplicación
todo-o-nada. Columnas:

```text
code, name, type, roles, zone_code, address, address_reference,
district, province, department, country, latitude, longitude,
time_zone, service_time_minutes, external_system, external_reference
```

`roles` acepta `ORIGIN`, `DESTINATION` o ambos separados por coma. Se mantiene como una
columna en lugar de dos banderas `can_origin` / `can_destination`: expresa exactamente lo
mismo, ya está implementada y probada, y una sola columna crece sin cambiar la plantilla si el
vocabulario se amplía.

Identidad de importación: `code`, único por compañía. Un código ya presente se **omite**, no se
duplica ni se trata como error, de modo que resubir el mismo archivo es seguro.

---

## 12. Legacy

`tms.origin` (V6) y `tms.destination` (V7) siguen existiendo como tablas **congeladas**:

- ningún código de aplicación las lee ni las escribe;
- ninguna clave foránea las referencia;
- el rol de ejecución `tms_app` perdió `INSERT`, `UPDATE` y `DELETE` sobre ellas.

Conservan `SELECT` a propósito: son el camino de recuperación si el merge por código de V14
unió dos lugares que en realidad eran distintos (deuda D-5 del ADR). Eliminarlas es una
migración posterior, cuando V23 se haya ejecutado contra una base de datos real.
