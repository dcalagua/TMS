# Runbook · cargar la demo PLAPASA en TMS QAS

> Pasos exactos para dejar el tenant **PLAPASA (demo)** listo en TMS QAS una vez desplegado el backend
> con V59–V69 (rama integrada `feature/tms-enterprise-overnight`: re-apilado por vehículo, geometría en
> pantalla, régimen fiscal de Ecuador y guías de remisión). El guion de la demo es [`PLAPASA_TMS_DEMO.md`](PLAPASA_TMS_DEMO.md).
>
> **Nada de esto lo hizo quien escribió este documento**: todo se validó en local
> (`PlapasaDemoSeedIntegrationTest`, Testcontainers). Cada paso que toca QAS o el EWM compartido
> necesita **autorización de Dennis para ese lote** (regla de la suite). Este documento **no contiene
> ningún secreto**: donde dice `<…>`, el valor vive en el gestor de secretos del equipo o en el panel
> del despliegue, nunca en el repositorio ni en un chat.

Orden: **despliegue → Flyway en V69 → cuentas de Auth → seed → régimen fiscal EC → credencial y webhook →
conector del EWM → perfiles → pedidos (y guía) → smoke.** Cada paso dice cómo se comprueba antes de seguir.

---

## 1 · Desplegar el backend

1. Confirmar **qué rama sigue cada servicio** en Render y Amplify (QAS-H1, `docs/operations/PROMOTION.md`).
2. Variables del servicio `tms-api` (además de las de `docs/environments/QAS.md`):

   | Variable | Valor | Por qué |
   |---|---|---|
   | `TMS_WEBHOOK_SECRET_KEY` | `<32+ caracteres aleatorios, del gestor de secretos>` | Sin ella no hay suscripciones de webhook: el EWM nunca se entera de un plan |
   | `TMS_ACCRUAL_SWEEP_ENABLED` | `false` **en el primer arranque** | Riesgo R-1 del informe de la mañana: si no, provisiona 31 días de viajes con la configuración por defecto |
   | `TMS_COST_ALLOCATION_SWEEP_ENABLED` | `false` **en el primer arranque** | Ídem, 62 días de repartos |
   | `TMS_CORS_ALLOWED_ORIGINS` | la URL del frontend QAS | — |

3. Desplegar. El backend corre Flyway al arrancar (perfil `prod`).

## 2 · Comprobar Flyway en V69

```bash
curl -s https://<api-qas>/actuator/health            # "UP"
curl -s https://<api-qas>/api/v1/system/info          # campo "commit" = el commit desplegado
```

```sql
-- sólo lectura, con la cadena de QAS del gestor de secretos
SELECT max(version::int) AS latest, count(*) FILTER (WHERE NOT success) AS failed
FROM tms.flyway_schema_history WHERE version IS NOT NULL;
-- esperado: latest = 69, failed = 0
```

Si `latest` < 69, **parar**: el seed y el guion usan V59–V69 (`company_settings.reference_load_height_m`,
`cost_allocation_basis`, `rate_card.amount_per_loading_meter`, `material_profile`, el régimen fiscal de V66,
las guías internacionales de V69…).

## 3 · Crear las cuentas en Supabase Auth del proyecto **TMS** (no el del EWM)

Supabase Studio › Authentication › Users › **Add user** › «Create new user», con «Auto Confirm User»:

| Correo | Para qué |
|---|---|
| `admin@plapasa.demo` | Administración de la organización (ve todo, crea credenciales, finanzas) |
| `planner@plapasa.demo` | Planificación (el que conduce la demo) |
| `finanzas@plapasa.demo` | COMPANY_ADMIN: aprueba la factura de TRLIT (quien la registra, admin@, no puede: cuatro ojos) |
| `transportista.trlit@plapasa.demo` | Portal del transportista TRLIT |

Las contraseñas las elige quien crea las cuentas y van al gestor de secretos. **Crear las cuentas
ANTES del seed**: el seed enlaza las personas buscándolas por correo. Si se crean después, volver a
correr el seed (es re-ejecutable) y listo.

## 4 · Correr el seed

```bash
psql "<cadena de conexión de QAS>" -v ON_ERROR_STOP=1 -f supabase/seeds/plapasa_demo_seed.sql
psql "<cadena de conexión de QAS>" -v ON_ERROR_STOP=1 -f supabase/seeds/plapasa_demo_seed.sql   # otra vez: no cambia nada
```

Comprobar (sólo lectura):

```sql
SELECT c.code, c.name, c.time_zone, s.default_country, s.dispatch_confirmation_mode,
       s.reference_load_height_m, s.cost_allocation_basis
FROM tms.company c JOIN tms.company_settings s ON s.company_id = c.id
WHERE c.code = 'QAS-PLAPASA';
-- QAS-PLAPASA | PLAPASA (demo) | America/Guayaquil | EC | HYBRID | 2.40 | EFFECTIVE_VOLUME

SELECT email, auth_user_id IS NOT NULL AS puede_entrar
FROM tms.app_user WHERE email LIKE '%@plapasa.demo' ORDER BY email;
-- las cuatro en true

SELECT (SELECT count(*) FROM tms.location l   WHERE l.company_id = c.id) AS ubicaciones,   -- 5
       (SELECT count(*) FROM tms.route r      WHERE r.company_id = c.id) AS rutas,         -- 4
       (SELECT count(*) FROM tms.vehicle v    WHERE v.company_id = c.id) AS vehiculos,     -- 4
       (SELECT count(*) FROM tms.driver d     WHERE d.company_id = c.id) AS conductores,   -- 3
       (SELECT count(*) FROM tms.rate_card rc WHERE rc.company_id = c.id) AS tarifas       -- 1
FROM tms.company c WHERE c.code = 'QAS-PLAPASA';
```

Y en la aplicación: entrar como `planner@plapasa.demo` → la empresa **PLAPASA (demo)** aparece y
QAS-LIMA **no** (el planificador sólo tiene membresía en PLAPASA).

## 5 · Régimen fiscal de Ecuador

El seed **no escribe ningún override fiscal**: con `default_country = 'EC'` la empresa ya está en el
régimen de Ecuador (ADR-027), con los valores de fábrica del despliegue (`tms.finance.tax.ec.*`), que
alcanzan para la demo. Este paso es **comprobar**, y cambiar sólo si el contador de PLAPASA lo pide.

**Comprobar** como `admin@plapasa.demo`, empresa PLAPASA: Administración › Empresa › tarjeta
**«Régimen fiscal»**, o

```bash
curl -s https://<api-qas>/api/v1/settlement/fiscal-settings \
     -H "Authorization: Bearer <token de admin@plapasa.demo>" -H "X-Company-Id: <id de PLAPASA>"
```

| Campo | Esperado |
|---|---|
| `regime` / `country` | `EC` / `EC` |
| `fiscalCurrency` · `vatLabel` | `USD` · `IVA` |
| `vatRate` · `freightVatRate` · `allowedVatRates` | 15 · **0** · 0, 5, 15 |
| `documentKinds` | 01 factura · 04 nota de crédito · 05 nota de débito |
| `documentNumber.placeholder` · `taxId` | `001-001-000000123` · RUC, 13 dígitos (`EC_RUC`) |
| `withholdings[0]` | `EC_INCOME_TAX_WITHHOLDING` · código **310** · **1 %** · base `SUBTOTAL` · **activa** |
| `withholdings[1]` | `EC_VAT_WITHHOLDING` · 70 % · **inactiva** (sólo agentes de retención de IVA designados) |
| `electronicValidation` | `EXTERNAL_DEPENDENCY` (el SRI no se consulta) |

**Cambiar** (sólo con el dato del contador; permiso `settlement.tax:manage`, administradores): la tarjeta
«Régimen fiscal» → «Cambiar régimen», o `PUT /api/v1/settlement/fiscal-settings` — **reemplaza entera**
la configuración de la empresa; un campo vacío hereda el valor de fábrica. Ejemplo, si PLAPASA fuera
agente de retención de IVA:

```json
{
  "fiscalCountry": "EC",
  "vatRate": null,
  "freightVatRate": null,
  "withholdings": [
    { "kind": "EC_INCOME_TAX_WITHHOLDING", "enabled": true, "code": "310", "rate": 1.00 },
    { "kind": "EC_VAT_WITHHOLDING", "enabled": true, "code": null, "rate": 70.00 }
  ]
}
```

Queda auditado (`UPDATE` sobre `COMPANY`, sección `FISCAL_REGIME`). Una factura ya recibida conserva el
régimen con el que entró. **Ojo:** el 1 % del código 310 está marcado en ADR-027 «a confirmar» por la
resolución NAC-DGERCGC26-00000009 (vigente desde 2026-03-01).

## 6 · Credencial de integración del EWM en el TMS

Como `admin@plapasa.demo`, empresa PLAPASA: **Administración › Integraciones › Nueva credencial**
(o `POST /api/v1/integration-clients` con `X-Company-Id`).

- Nombre: `EWM PLAPASA (GYE-CD)`
- Scopes — los seis que llama el conector del EWM (`integration/tms` del EWM, contrato §7.7):

  | Scope | Lo usa el EWM para |
  |---|---|
  | `integration.shipment:read` | `GET /integration/v1/shipments/{n}` y la lista (leer el plan, la POD, reconciliar) |
  | `integration.dispatch:write` | `POST /integration/v1/dispatch-confirmations` (el SLS) |
  | `integration.warehouse-milestone:write` | `POST /integration/v1/warehouse-milestones` (carga iniciada / lista / anulada) |
  | `integration.material:write` | `POST /integration/v1/material-profiles/batch` (perfiles, `TMS_MATP`) |
  | `integration.order:read` | `GET /integration/v1/orders/{n}` y la lista (anulaciones del TMS) |
  | `integration.warehouse-order-event:write` | `POST /integration/v1/warehouse-order-events` (anulaciones del EWM) |

  La credencial del **ERP** es otra (`integration.order:write` + `integration.document:write`): la crea el
  script del paso 9.

- El secreto (`tmsc_<id>.tmss_<secreto>`) **se muestra una sola vez**: al gestor de secretos.
- Anotar el **id de la empresa** PLAPASA en el TMS (`GET /api/v1/me` → `companies[]`, o
  `SELECT id FROM tms.company WHERE code = 'QAS-PLAPASA'`): va en `tmsCompanyId`.

## 7 · Conector del EWM y suscripción de webhook (la danza de tres pasos)

El EWM firma-verifica los avisos con el secreto de la suscripción, y la suscripción apunta a una URL
que lleva el id del conector. Por eso (mismo orden que `scripts/e2e/tms-ewm/lib/setup_link.py`):

**7.1 · EWM, conector con un secreto provisional.** Como administrador del EWM (permiso `CONFIGURE`),
tenant PLAPASA, `PUT https://ewm.ebim.pe/api/v1/admin/integrations/tms/connector`:

```json
{
  "baseUrl": "https://<host-del-backend-tms-qas>",
  "credential": "<tmsc_…tmss_… del paso 6>",
  "webhookSecret": "<provisional: cualquier valor aleatorio, se reemplaza en 7.3>",
  "sourceSystem": "EWM_EBIM",
  "tmsCompanyId": "<id de la empresa PLAPASA en el TMS, paso 6>",
  "defaultWarehouseId": null,
  "includeSlsDocument": true,
  "active": true
}
```

- `baseUrl` es el host **sin** `/api/v1` (el EWM agrega `/integration/v1/…`). Ojo: la variable del
  frontend `VITE_API_BASE_URL` termina en `/api/v1`; acá va sin eso.
- `sourceSystem` es el **productor** (el EWM), no el origen de los pedidos.
- El EWM necesita `WMS_INTEGRATION_SECRET_KEY` en su despliegue (si no, falla cerrado y no guarda).
- Guardar de la respuesta el **`id`** y el **`webhookPath`** (`/api/v1/integration/tms/webhooks/<id>`).
  Guardar el conector **crea los destinos** `TMS_DISPATCH`, `TMS_MILESTONES` y, con la v1.1, el de
  perfiles y el de eventos de pedido.

**7.2 · TMS, la suscripción.** Como `admin@plapasa.demo`: Administración › Integraciones › Webhooks ›
Nueva (o `POST /api/v1/webhooks` con `X-Company-Id`):

```json
{
  "name": "EWM PLAPASA (GYE-CD)",
  "description": "Plan, cambios, anulaciones y POD hacia el EWM",
  "targetUrl": "https://ewm.ebim.pe<webhookPath de 7.1>",
  "eventTypes": ["SHIPMENT_CONFIRMED", "SHIPMENT_CHANGED", "SHIPMENT_CANCELLED",
                 "DELIVERY_RESULT_RECORDED", "ORDER_CANCELLED"]
}
```

El secreto de firma **se muestra una sola vez**: al gestor de secretos.

**7.3 · EWM, el secreto real.** El mismo `PUT` de 7.1 con `"webhookSecret": "<el de 7.2>"` (y la
`version` que devolvió el GET del conector). Un secreto ausente se conserva; vacío se rechaza.

**7.4 · Comprobar la clave de los pedidos.** En el EWM, `GET /api/v1/outbound-orders?search=SO-2026-10040`
→ el campo `sourceSystem` del pedido. Tiene que ser **`MANUAL`** (así lo dejó el cargador de la demo del
EWM). Si dice otra cosa, exportar `TMS_SOURCE_SYSTEM=<ese valor>` antes del paso 9: el plan y el SLS
se encuentran **byte a byte** por (`sourceSystem`, número del cliente), y con otro valor el EWM no
reconoce ningún pedido del plan.

## 8 · Perfiles de material — antes de los pedidos

Un pedido copia el perfil **al entrar**; uno que llega después no reescribe pedidos ya creados.

- **Con el EWM v1.1 desplegado (camino de la demo):** EWM › Integraciones › TMS › **Reenviar perfiles
  al TMS** (o `POST /api/v1/admin/integrations/tms/material-profiles/resend`). En el TMS, Maestros ›
  Perfiles de material: **25** materiales, origen **`EWM_EBIM`**.
- **Sólo si el EWM no tiene la v1.1 o el conector no está:**
  ```bash
  psql "<cadena de QAS>" -v ON_ERROR_STOP=1 -f supabase/seeds/plapasa_demo_material_profiles_fallback.sql
  ```
  25 materiales con origen **`PLAPASA_DEMO_SEED`** y los mismos valores del blueprint del EWM. Si el EWM
  manda después, su copia más nueva reemplaza ésta.

Comprobar con el script (sólo lectura):

```bash
TMS_API_URL=https://<host-del-backend-tms-qas> SUPABASE_URL=https://<proyecto-tms>.supabase.co \
SUPABASE_ANON_KEY=<anon key del proyecto TMS> DEMO_PASSWORD=<contraseña de admin@plapasa.demo> \
python3 scripts/demo/plapasa_tms_demo.py check
# PLP-571 NESTABLE · PLP-111 NESTABLE · PLP-N105 NESTABLE · PLP-1601/1608/1623 STACKABLE  -> exit 0
```

(Las variables también aceptan `TMS_ADMIN_TOKEN` en vez de la contraseña.)

## 9 · Los pedidos del ERP

```bash
... python3 scripts/demo/plapasa_tms_demo.py orders
# si la demo no es el 20/10:  ... orders --service-date <fecha del camión, AAAA-MM-DD>
```

El script crea una credencial **`ERP PLAPASA (demo) <fecha>`** con `integration.order:write` e
`integration.document:write` (o usa
`TMS_ERP_TOKEN` si se exporta una), manda los seis pedidos de `scripts/demo/plapasa/orders.json` en
un `POST /integration/v1/orders/batch` y lista lo que ocupa cada uno. Esperado:

```
ERP -> TMS: 6 pedidos, 6 bien, 0 rechazados
  SO-2026-10040 -> OT-00000001 CREATED READY_FOR_PLANNING
      declarado 20.7570 m³ · ocupa 4.5831 m³ · 10 pilas · piso 2.3318 m² · alto 2.350 m · líneas sin medir 0
  SO-2026-10034 -> OT-00000002 CREATED READY_FOR_PLANNING
      declarado 25.8200 m³ · ocupa 25.8126 m³ · 55 pilas · piso 11.8455 m² · alto 2.320 m · líneas sin medir 0
  SO-2026-10041 -> OT-00000003 CREATED READY_FOR_PLANNING
      declarado 6.7128 m³ · ocupa 4.6893 m³ · 1 pilas · piso 0.1071 m² · alto 1.980 m · líneas sin medir 2
  SO-2026-10045 -> OT-00000004 CREATED READY_FOR_PLANNING
      declarado 9.5000 m³ · ocupa 0.7514 m³ · 50 pilas · piso 0.5513 m² · alto 1.363 m · líneas sin medir 0
  SO-2026-10046 -> OT-00000005 CREATED READY_FOR_PLANNING
      declarado 10.7320 m³ · ocupa 0.9751 m³ · 44 pilas · piso 0.8131 m² · alto 1.363 m · líneas sin medir 0
  SO-2026-10021 -> OT-00000006 CREATED NOT_READY
      declarado 3.1776 m³ · ocupa 0.6339 m³ · 8 pilas · piso 0.4766 m² · alto 1.363 m · líneas sin medir 1
```

(Los números `OT-…` dependen de cuántos pedidos haya numerado antes la empresa; las cifras no.)

Una segunda corrida contesta seis `UNCHANGED`. **Revocar** la credencial del ERP después de la demo
(Administración › Integraciones), o reutilizarla con `TMS_ERP_TOKEN`.

> Si un pedido sale `NOT_READY` con `CUTOFF_MISSED`, su fecha ya pasó: repetir con `--service-date`.

**Guía de remisión (opcional):**

```bash
... python3 scripts/demo/plapasa_tms_demo.py guide
# Guía de remisión 001-001-000000123 (SRI 06): CREATED · pedidos enlazados 1 · desconocidos []
```

Manda `scripts/demo/plapasa/remission_guide.json` por `POST /integration/v1/logistics-documents`
(`docs/integrations/INBOUND_API_V1.md` §15): comprobante 06, clave de acceso de 49 dígitos con
verificador válido (ambiente de pruebas), emisor 0999999999001, transportista TRLIT, placa GSD-1357,
pedido `MANUAL` / `SO-2026-10040`. Se ve en la sección «Documentos» del pedido y del viaje y en el portal
de TRLIT. Una segunda corrida da `UNCHANGED`. Mandarla después de los pedidos: si el pedido todavía no
existe, sale en `desconocidos` y se enlaza al reenviarla.

## 10 · Smoke (sólo lectura)

- [ ] `planner@plapasa.demo` entra; Maestros › Ubicaciones muestra 5 y ninguna `QAS-…`.
- [ ] Flota › Vehículos: CAM-01 GBA-1234, CAM-02 GBC-5678, CAM-03 GSC-2468, CAM-04 GSD-1357, todos de TRLIT.
- [ ] Comercial › Tarifarios: `TRLIT-2026-USD` en USD con metro lineal 6,50.
- [ ] Operación › Planificación › origen GYE-CD, fecha 21/10: las tarjetas de `SO-2026-10045/46` dicen
      0,75 m³ y 0,98 m³ (**no** 9,50 y 10,73). Cerrar sin crear el plan.
- [ ] Operación › Pedidos: la columna «Ocupa» de `SO-2026-10045` dice 50 pilas y 0,75 m³ frente a 9,50.
- [ ] Administración › Empresa › «Régimen fiscal»: Ecuador, IVA 15, flete 0, retención IR 310 1 % activa.
- [ ] `finanzas@plapasa.demo` entra y ve Operación › Auditoría de flete.
- [ ] EWM › Integraciones › TMS › Conector: credencial y secreto «configurados», última respuesta con hora.
- [ ] (Opcional, con autorización para crear datos) Ensayar el guion (b)–(e) y cancelar los planes
      después; un viaje despachado no se deshace.

## 11 · Después de configurar (antes o después de la demo)

1. **Finanzas**: mapeo contable (`/api/v1/settlement/gl-mappings`), disparador de provisión (queda
   `DISPATCHED`) y base de reparto (`EFFECTIVE_VOLUME`, ya puesta por el seed). Recién entonces volver a
   poner `TMS_ACCRUAL_SWEEP_ENABLED` y `TMS_COST_ALLOCATION_SWEEP_ENABLED` en `true` (o usar
   «Provisionar ahora» y «Recalcular» en la demo).
2. **Revocar** la credencial del ERP de la demo.
3. Para empezar de cero, ver `PLAPASA_TMS_DEMO.md` §6.

## Qué no hace este runbook

No crea ningún secreto en un fichero, no toca el EWM salvo su conector, no aplica migraciones a mano y no
borra datos. Los perfiles y los pedidos entran por la API (o, el respaldo de perfiles, por SQL
declarado como tal). La reconciliación del EWM sigue **apagada** de fábrica.
