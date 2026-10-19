# Hoja de ruta del producto · TMS by EBIM

> **Idioma.** Esta hoja está en **castellano** porque la lee el dueño del negocio. El
> código, los ADR y la documentación de arquitectura **siguen en inglés**, como el
> resto del repositorio (`CLAUDE.md`).
>
> Escrita el **2026-10-18** sobre `origin/dev` en `4d66af6`. Es documentación: no cambia
> código ni base. Reemplaza como hoja de ruta a `docs/product/ROADMAP_NEXT.md` (anterior,
> en inglés, desactualizada: ver §9.1) y resume el plan de
> `docs/roadmap/2026-10-enterprise/04_ROADMAP_ENTERPRISE.md` con las decisiones de hoy.
>
> Cada renglón lleva **su fuente** (un documento del repo o una decisión del dueño del
> negocio de hoy, marcada **[Edu 2026-10-18]**). Lo inferido y no escrito en ningún lado
> va como **«propuesta»**; lo que hay que verificar, **«a confirmar»**.

Hay un documento hermano en el EWM (`WMS-by-EBIM/docs/ROADMAP.md`). La §7
(dependencias cruzadas) es **idéntica** en los dos.

---

## 0 · En una frase por lector

- **Para negocio (Choco):** el TMS ya planifica, licita, ejecuta, entrega con POD y
  **concilia la factura del transportista contra lo que se acordó**, con impuestos de
  Perú y Ecuador. Lo que falta para cobrarle a un cliente en producción es **que exista
  producción**.
- **Para quien lo opera (Gyanina):** la carga anidable ya se ve en pantalla («Ocupa»);
  falta poder corregir la altura de referencia sin un desarrollador.
- **Para quien lo construye (TMS):** QAS está desplegado pero **nadie escribió qué
  versión de Flyway tiene** (QAS-H1), y hay tres decisiones (D-01, D-04, D-06) que
  frenan cerrar el ciclo 2.

---

## 1 · Dónde estamos hoy

Estados según `docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md` (`IMPLEMENTED` /
`PARTIAL`, estado general **NOT PRODUCTION CERTIFIED**), traducidos a la convención de
la suite: `SUPPORTED` · `LIMITED` · `EXTERNAL_DEPENDENCY` · `NOT_SUPPORTED`.

### 1.1 Pedidos y planificación

| Módulo | Estado | Evidencia |
|---|---|---|
| Locales, zonas, frecuencias, rutas | SUPPORTED | mapa, filas 2–3 |
| Pedidos (8 estados), división, programación, importación | SUPPORTED | mapa, filas 5–6, 24 |
| Planificación manual y automática (`HEURISTIC_V1`, `PLANNING_V2`) | SUPPORTED | mapa, filas 7–8 |
| Ruteo geodésico (sin proveedor de mapas) | LIMITED | mapa, fila 9 |
| **Optimización de rutas** (VRP con ventanas) | NOT_SUPPORTED — diferida por decisión | `CLAUDE.md` «Deferred by decision»; `04_ROADMAP_ENTERPRISE.md` ítem 1 |
| Perfil físico del material, apilable/anidable (ADR-022) | SUPPORTED | `docs/roadmap/2026-10-enterprise/02_APILABLES_ANIDABLES.md` |
| Re-apilado por vehículo y geometría en pantalla («Ocupa») | SUPPORTED (merge `1f99fb3`, `aafd9f4`) | `DECISIONS_LOG.md` (l.777), `frontend/tms-web/src/shared/geometry/Occupancy.tsx` |
| Editar altura de referencia / factor de llenado desde una pantalla | **a confirmar** | `MORNING_REPORT.md` §7 |
| Carga 3D / bin-packing | NOT_SUPPORTED (detrás del puerto `PlanningEngine`) | ADR-022 «What this ADR does not do» |

### 1.2 Transportistas y ejecución

| Módulo | Estado | Evidencia |
|---|---|---|
| Flota: transportistas, tipos, vehículos, choferes, disponibilidad | SUPPORTED | mapa, fila 4 |
| Tarifas (12 componentes) + tarifa por metro lineal | SUPPORTED | mapa, fila 10; `PHASE_MAP.md` fase 45 |
| Licitación en cascada (sin planificador en segundo plano) | SUPPORTED con límite | mapa, fila 11 |
| Ejecución del viaje, POD, citas de destino (V41) | SUPPORTED | mapa, filas 12, 13, 15 |
| Portal del transportista (ADR-018) | SUPPORTED | mapa |
| Seguimiento: puerto `TrackingProviderPort` sin adaptador, sin mapa en vivo, sin llegada automática | LIMITED | mapa, fila 14; `docs/product/KNOWN_LIMITATIONS.md` 5.10 |
| Torre de control: sólo por viaje, sin SLA ni deduplicación | LIMITED | mapa, fila 18 |
| Programación de recursos de flota | LIMITED | mapa, fila 17 |
| Portal del cliente/consignatario | NOT_SUPPORTED | `04_ROADMAP_ENTERPRISE.md` ítem 6 |
| Multi-tramo / transbordo / consolidación | NOT_SUPPORTED (no hay modelo) | ADR-026 «Not done» |

### 1.3 Finanzas de fletes («el TMS valida, el ERP paga»)

| Módulo | Estado | Evidencia |
|---|---|---|
| Conciliación de facturas con tolerancias y líneas por cargo | SUPPORTED | `03_FINANZAS_FLETES.md` F-1 |
| Provisión (accrual) + mapeo contable + cierre | SUPPORTED, barridos **apagados de fábrica** | F-2; `DECISIONS_LOG.md` (l.795, `0570462`) |
| Régimen fiscal por país: **Perú, Ecuador, genérico** | SUPPORTED | ADR-027 |
| Estado de pago (informado por el ERP, cuatro ojos) — **sin mover dinero** | SUPPORTED | ADR-023, F-4, TMS D-01 |
| Costo por pedido/cliente, KPI de finanzas | SUPPORTED | ADR-026, F-5 |
| Documentos logísticos **recibidos** (guía de remisión SRI 06, etc.) | SUPPORTED — nunca se emiten | ADR-028 (V69) |
| Validación en línea del comprobante (`FiscalDocumentValidationPort`) | EXTERNAL_DEPENDENCY (puerto apagado) | ADR-024, ADR-027 |
| Tarifa de venta y margen | NOT_SUPPORTED | ADR-026 «Not done» |
| Moneda extranjera (FX) | NOT_SUPPORTED | ADR-023/024/027 |

### 1.4 Integración y plataforma

| Módulo | Estado | Evidencia |
|---|---|---|
| API de socios v1, webhooks firmados HMAC, `GET /integration/v1/ping` | SUPPORTED | `docs/integrations/INBOUND_API_V1.md`, `docs/integrations/WEBHOOKS_V1.md` |
| Sincronización con el EWM v1.1 (S-1…S-4) | SUPPORTED | `01_SINCRONIZACION_EWM_TMS.md`, ADR-025 §1–6 |
| Citas de muelle unificadas con el EWM (S-5) | NOT_SUPPORTED — propuesta, TMS D-04 | ADR-025 §7 |
| Integraciones de entrada sin almacén de mensajes muertos ni reproceso por rango | LIMITED | mapa, fila 19 |
| Webhooks de salida sin dead-letter, sin replay, sin UI del payload | LIMITED | mapa, fila 20 |
| Conector de ERP empaquetado | NOT_SUPPORTED (sólo la API publicada) | `KNOWN_LIMITATIONS.md` §6 |
| IAM, aprovisionamiento desde MasterAdmin (ADR-012), suspensión del tenant (ADR-021) | SUPPORTED | mapa, fila 1; ADR-021 |
| Entitlements (sólo `appActive`; registro vendible vacío) | LIMITED | mapa, fila 33 |
| Observabilidad | LIMITED | mapa, fila 25 |

### 1.5 Ambientes

| Ambiente | Estado | Evidencia |
|---|---|---|
| QAS | **Desplegado** (backend en Render, front en Amplify) **[Edu 2026-10-18]**; rama `qas` al día con `dev`. **Versión de Flyway no observada en ningún documento** (QAS-H1) | `docs/environments/QAS.md`; `TMS_QAS_RUNTIME_CERTIFICATION.md` §QAS-H1 |
| PRD | **No existe** («será otro proyecto Supabase») | `docs/environments/QAS.md`, `00_ESTADO_ACTUAL.md` §1 |
| CI | `.github/workflows/ci.yml` escrito; **nunca corrió** | `docs/operations/PROMOTION.md` §7 |

> Las URL de QAS **no se escriben en el repositorio** a propósito
> (`docs/environments/QAS.md`); los documentos usan `<api-qas>` y `<web-qas>`.

---

## 2 · Horizonte 0 · Antes de producción

Esfuerzo: **S** ≤ 2 días · **M** ≤ 1 semana · **L** > 1 semana. Crew según el contrato
§12 de la suite (el `CLAUDE.md` del TMS todavía no lo declara — **propuesta**: agregarlo):
Lia estrategia · Choco PMM · Gyanina UX · **TMS** Dev · Renato QA · Dennis aprueba.

| # | Ítem | Dueño | Esf. | Depende de | Criterio de aceptación | Fuente |
|---|---|---|---|---|---|---|
| H0-1 | **Cerrar QAS-H1**: observar y escribir la versión de Flyway de QAS (debe ser 69), `/actuator/health` y el front de Amplify; confirmar qué rama sigue cada servicio | TMS | S | — | Acta con versión, rama y hora; `QAS.md` deja de decir «ningún despliegue se ha observado» | `04_ROADMAP_ENTERPRISE.md` 0.2; `docs/demo/PLAPASA_TMS_RUNBOOK.md` §1–§2; `PROMOTION.md` §1 |
| H0-2 | **CI corriendo** en GitHub sobre cada PR (PostGIS 17 con Testcontainers) | TMS | S | push autorizado | Corrida verde visible | `04_ROADMAP_ENTERPRISE.md` 0.4; `PROMOTION.md` §7 |
| H0-3 | **Podar ramas y worktrees** ya contenidos en `dev` | TMS | S | — | `git branch` sin ramas mergeadas | `04_ROADMAP_ENTERPRISE.md` 0.3 |
| H0-4 | **Decisión TMS D-01**: confirmar que el TMS **no mueve dinero** (provisiona, calcula, exporta y registra el pago que informa el ERP) | Dennis + Lia | S | — | D-01 pasa de «PENDIENTE» a «Tomada» en `DECISIONES.md` | `DECISIONES.md`; `MORNING_REPORT.md` §5 |
| H0-5 | **Decisión TMS D-06 y defaults de Ecuador**: validar con la contabilidad del cliente IGV 18 %, detracción 4 % desde S/ 400, retención 3 %, 30 días (Perú) e **IR 1 % código 310** tras la Res. **NAC-DGERCGC26-00000009** (Ecuador) | Lia (cliente) + Dennis | S | contador de PLAPASA | Porcentajes confirmados por escrito; `tms.finance.tax.*` ajustado | `DECISIONES.md` D-06; ADR-027 §3; `docs/demo/PLAPASA_TMS_DEMO.md` §9 |
| H0-6 | **Decisión TMS D-04**: go/no-go de las citas de muelle unificadas (S-5) | Dennis | S | — | ADR-025 §7 pasa a Accepted o Rejected | `DECISIONES.md` D-04; ADR-025 §7 |
| H0-7 | **Crear PRD**: proyecto Supabase aparte, Render, Amplify, variables por rama | TMS · decide Dennis | L | H0-1, H0-2 | `/actuator/health` en PRD; Flyway en la última versión | `04_ROADMAP_ENTERPRISE.md` 0.5; `QAS.md` |
| H0-8 | **Riesgos de migración** antes de PRD: 27 CHECK que se borran sin `IF EXISTS` (R-3); revisar el backfill de V62 (corregido en `0474894`) | TMS · Renato | S | — | Migración limpia sobre una copia de QAS | `MORNING_REPORT.md` §7 |
| H0-9 | **Puerta de promoción**: protección de la rama `qas`, los 7 E2E autenticados contra un ambiente real, un rollback ensayado | TMS · Renato | M | H0-1 | Los tres con acta | `PROMOTION.md` §7 |
| H0-10 | **Observabilidad**: algo que mire `/actuator/metrics`; alertas de webhooks y barridos | TMS | M | H0-7 | Una alerta provocada llega a quien atiende | `PROMOTION.md` §7; mapa, fila 25 |
| H0-11 | **Suspensión del tenant también en el despachador de webhooks** | TMS | S | PostgreSQL para validar | Un tenant suspendido no emite webhooks | ADR-021 «Consequences» |
| H0-12 | **Secretos de PRD**: `TMS_WEBHOOK_SECRET_KEY`, credenciales de integración; custodia y rotación | TMS + Dennis | S | H0-7 | Política escrita; nada en el repo | `PLAPASA_TMS_RUNBOOK.md` §1 |
| H0-13 | **Respaldos y RPO/RTO** del proyecto Supabase de PRD | TMS + Dennis | M | H0-7 | Restauración ensayada — **propuesta** (no hay documento de respaldos del TMS) | — |
| H0-14 | **Auditoría `/qas`** del lote que llega a PRD | Renato | M | H0-7 | Sin P0/P1 abiertos | contrato §12 (crew) |

---

## 3 · Horizonte 1 · Próximas 4–6 semanas

Ordenado por **valor de venta ÷ riesgo**: primero lo que hace que un segundo cliente
no necesite un desarrollador, después lo que pide el cliente que ya está (PLAPASA,
Ecuador), y al final lo que depende de un tercero.

| # | Ítem | Por qué ahora | Dueño | Esf. | Criterio de aceptación | Fuente |
|---|---|---|---|---|---|---|
| H1-1 | **Integración EWM⇄TMS en autoservicio**. Del lado del TMS: el `ping` que el EWM prueba, la credencial `tmsc_…` con sus seis scopes y el alta del webhook firmado, todo sin intervención de EBIM | Cada negocio conecta su EWM con su TMS desde la plataforma; hoy son tres pasos manuales por API | TMS + WMS · Gyanina | M | Un admin conecta los dos en QAS siguiendo la pantalla del EWM | **EN CURSO** en el EWM (`feature/tms-connect-selfservice`) · **[Edu 2026-10-18]** (c) · `PLAPASA_TMS_RUNBOOK.md` §6–§7 |
| H1-2 | **Impuestos: más países** — Colombia, Chile, México (hoy caen en el régimen genérico, sin retenciones). Cada país = un `TaxRegime`, sus `WithholdingKind`, un bloque `tms.finance.tax.<país>` y una migración | Los impuestos son **internacionales por definición del producto**: el país fiscal es de la empresa | TMS · Lia (orden de países) | M por país | Un país nuevo con pruebas de cálculo y una factura de ejemplo conciliada | **[Edu 2026-10-18]** (b); ADR-027 «Consequences» |
| H1-3 | **Régimen del transportista como dato maestro** (RIMPE, contribuyente especial en Ecuador) y umbrales acumulados por proveedor | Sin eso la retención se calcula igual para todos | TMS | M | Retención distinta según el régimen del proveedor | ADR-027 «Not done»; `PLAPASA_TMS_DEMO.md` §(g) |
| H1-4 | **Geometría por pedido en el plan** (`orders[].geometry`) y `GEOMETRY_VARIANCE` contra el re-apilado | El EWM recibe `loadingMeters` vacío | TMS + WMS | S | Contrato decidido y E2E verde | `DECISIONS_LOG.md` (l.811) |
| H1-5 | **Perfiles físicos medidos en planta** (incremento de anidado; hoy Δh 1,2 cm es un supuesto) | El número de camiones depende de ese incremento | Lia (cliente) + TMS | S | Perfiles de PLAPASA cargados con medidas reales | `02_APILABLES_ANIDABLES.md` §2; `PLAPASA_TMS_DEMO.md` §9 · **pendiente de confirmación del negocio** |
| H1-6 | **Deuda de pantalla del ciclo 2**: editar términos de pago; selectores para usuarios sólo de liquidación; botones ocultos por capacidad; editar altura de referencia; cotización de flota propia como fuente de costo; cajón «Reparto del viaje» | Hoy hay pantallas que muestran un botón que el backend rechaza | TMS + Gyanina | M | Cada ítem con prueba de pantalla | `MORNING_REPORT.md` §7; `CERTIFICATION.md` §7 |
| H1-7 | **Puntos abiertos de los documentos recibidos**: dígito verificador mod-11 de la clave de acceso, vínculo por destinatario, comparar placa/transportista con el viaje, acción de auditoría `FISCAL_REGIME_CHANGED` | Un documento mal tipeado hoy entra | TMS | S | Una prueba por punto | ADR-028 «Open points»; `DECISIONS_LOG.md` (l.836, l.876) |
| H1-8 | **Citas de muelle unificadas con el EWM (S-5)** + ETA hacia el almacén (`TRUCK_ETA_UPDATED`) | Dos agendas de la misma puerta | TMS + WMS | M | **Sólo si D-04 se aprueba** | ADR-025 §7 |
| H1-9 | **Proveedor GPS real** (adaptador de `TrackingProviderPort`; el del transportista de PLAPASA, TRLIT) | La posición del camión hoy se teclea | TMS | M | Posiciones reales llegando de un proveedor en QAS | `04_ROADMAP_ENTERPRISE.md` ítem 2; `PLAPASA_TMS_DEMO.md` §(g) · **EXTERNAL_DEPENDENCY** (credenciales del proveedor) |
| H1-10 | **Webhooks e integraciones robustas**: almacén de mensajes muertos, replay por rango, ver el payload | Hoy un webhook fallido se pierde de vista | TMS | M | Replay de un rango en QAS | mapa, filas 19–20 |
| H1-11 | **Portal del cliente/consignatario** (seguimiento y POD) | Lo pide cada cadena que recibe | TMS + Gyanina · Lia (protección de datos) | L | Contrato de lectura con token + decisión de protección de datos | `04_ROADMAP_ENTERPRISE.md` ítem 6; `docs/product/ROADMAP_NEXT.md` §2 |
| H1-12 | **Optimización de rutas** (VRP con ventanas, OR-Tools detrás de `PlanningEngine`) | **Sólo si hay un cliente con 30+ paradas/día**; si no, pasa a H2 | TMS | L | ADR propio; mismo pedido, menos kilómetros, medido | `04_ROADMAP_ENTERPRISE.md` ítem 1; `CLAUDE.md` |
| H1-13 | Higiene: 21 avisos de lint, el chunk > 500 kB | Barato y molesta en cada build | TMS | S | `lint` limpio; chunk partido | `MORNING_REPORT.md` §7 |

**Por qué este orden.** H1-1 y H1-2 son lo que cambia cuánto cuesta vender: sin
autoservicio cada cliente necesita a un desarrollador, y sin más países el producto
sólo se vende en dos. H1-3 a H1-7 cumplen con PLAPASA. H1-8 a H1-12 esperan una
decisión, un tercero o un cliente que lo justifique.

---

## 4 · Horizonte 2 · El trimestre siguiente (enterprise)

| # | Ítem | Estado hoy | Fuente |
|---|---|---|---|
| H2-1 | **Carga 3D / estantes 2D** detrás del puerto `PlanningEngine` (sin pilas de materiales mezclados) | NOT_SUPPORTED | ADR-022; `04_ROADMAP_ENTERPRISE.md` ítem 11 |
| H2-2 | **Multi-tramo, transbordo y consolidación** (y el reparto de costo por tramo) | Sin modelo | ADR-026; ítem 5 |
| H2-3 | **Conectores de ERP empaquetados** (SAP B1, S/4, Oracle, Odoo) sobre la API publicada | NOT_SUPPORTED | `KNOWN_LIMITATIONS.md` §6; `CLAUDE.md` «Deferred» |
| H2-4 | **Tarifa de venta y margen** por cliente | NOT_SUPPORTED | ADR-026; ítem 4 |
| H2-5 | **Scorecard de transportistas** (a tiempo, rechazos de licitación, discrepancias) | — | ítem 8 |
| H2-6 | **Reclamos por daño o faltante** con evidencia; la nota de crédito la **recibe** del ERP, no la emite | — | ítem 7, ajustado a **[Edu 2026-10-18]** (a) |
| H2-7 | **Validación en línea** de los comprobantes **recibidos** (consulta a SUNAT/SRI) y conciliación de constancias de detracción | Puerto apagado | ítem 10; ADR-024 — **a confirmar** que consultar (no emitir) entra en alcance |
| H2-8 | **Analítica de transporte**: torre de control con SLA y deduplicación, KPI más allá de 92 días | LIMITED | mapa, fila 18; `KNOWN_LIMITATIONS.md` §7 |
| H2-9 | **Agentes IA** sobre los datos del TMS (excepciones, licitación) — propuesta, por simetría con la Flota IA del EWM | — | **propuesta** |
| H2-10 | **Multi-país operativo**: moneda extranjera (FX), formatos de documento recibido por país | NOT_SUPPORTED | ADR-023/024/027; ADR-028 |
| H2-11 | **SSO del hub de identidad** de la suite | No mencionado en los documentos del TMS | contrato de la suite §2 — **propuesta** |
| H2-12 | **Addons y precio** (Lia): llenar el registro vendible de entitlements | Registro vacío | mapa, fila 33 — **propuesta** |
| H2-13 | **Huella de carbono** por viaje (GLEC) | — | ítem 9 |
| H2-14 | Pendientes de `KNOWN_LIMITATIONS.md` que siguen valiendo: pantalla de administración de la organización, correo, marcar listos en lote, lectura de auditoría, borrar evidencia | LIMITED | `KNOWN_LIMITATIONS.md` §5 (revisar: el documento es de V35) |

---

## 5 · Horizonte 3 · Visión (propuesta)

- **Una sola línea de tiempo del pedido en la suite**: del rack (EWM) a la firma del
  consignatario (TMS), cada dato con su dueño (`01_SINCRONIZACION_EWM_TMS.md` §1).
- **Mapa de flota en vivo y llegada automática** (hoy diferidos por decisión en
  `CLAUDE.md`) cuando haya proveedores GPS reales.
- **Planificación optimizada por defecto** con explicación de cada decisión, sin
  perder el plan manual.
- **Red multimodal multi-país** sobre el modelo multi-tramo de H2.

---

## 6 · Fuera de alcance (explícito)

| Qué | Por qué | Fuente |
|---|---|---|
| **Emitir, firmar o enviar** facturas, guías de remisión (GRE) u otros comprobantes a SRI/SUNAT | El TMS sólo **recibe** esos datos por integración. Esto **retira** el ítem 3 de `04_ROADMAP_ENTERPRISE.md` | **[Edu 2026-10-18]** (a); ADR-028 |
| **Mover dinero**: pagos, archivos de banco, cuentas bancarias, libro mayor | El ERP paga | TMS D-01 (pendiente de confirmar, implementado así); ADR-023 |
| Facturación de ventas | Es del ERP | `03_FINANZAS_FLETES.md` |
| Almacén, patio, inventario | Es el EWM | `KNOWN_LIMITATIONS.md` §6; `CLAUDE.md` (TMS independiente del EWM) |
| Compartir tablas con el EWM | Integración sólo por API, eventos y contratos | `CLAUDE.md` §Product |
| Kafka, microservicios, event sourcing, Supabase Realtime | Diferidos por decisión (no prohibidos para siempre) | `CLAUDE.md` «Deferred by decision» |

---

## 7 · Dependencias cruzadas EWM ⇄ TMS

> **Tabla compartida.** Este bloque es idéntico en EWM:`docs/ROADMAP.md` §7 y en
> TMS:`docs/roadmap/ROADMAP.md` §7; se edita en los dos a la vez. Rutas con prefijo
> `EWM:` o `TMS:` según el repositorio.

| # | Dependencia | Lado EWM | Lado TMS | Estado | Decide / aprueba | Fuente |
|---|---|---|---|---|---|---|
| X-1 | **Conexión en autoservicio** | Pantalla «Conectar con el TMS»: ping, API key, URL del webhook, secreto de firma | `GET /integration/v1/ping`; credencial `tmsc_…` con 6 scopes; alta del webhook firmado | **EN CURSO** | Edu | [Edu 2026-10-18]; EWM:`docs/integrations/TMS_CONNECTOR_V1.md` §2.2; TMS:`docs/integrations/INBOUND_API_V1.md`, TMS:`docs/demo/PLAPASA_TMS_RUNBOOK.md` §6–§7 |
| X-2 | **Orden de despliegue** | V54 → V58 en orden, después de que el TMS esté arriba | V59–V69 primero; Flyway observado en 69 | Pendiente (EWM base en V53; TMS QAS sin versión observada, QAS-H1) | Dennis | EWM:`docs/tms-sync/DESPLIEGUE.md` §2–§4; TMS:`docs/environments/QAS.md` |
| X-3 | **Perfil físico apilable/anidable** | Dueño del dato (V56, interfaz `TMS_MATP`) | Lo usa para la geometría de carga (ADR-022) | Hecho; **incrementos de anidado a medir en planta** | PLAPASA (negocio) | EWM:`docs/tms-sync/DESIGN.md` §2; TMS:`docs/roadmap/2026-10-enterprise/02_APILABLES_ANIDABLES.md` §2 |
| X-4 | **Geometría por pedido en el plan** (`orders[].geometry`) | `loadingMeters` hoy llega vacío | No se emite; falta decidir altura de referencia, re-apilado por vehículo o ambos | Decisión de contrato pendiente | TMS Dev + WMS | TMS:`docs/roadmap/2026-10-enterprise/DECISIONS_LOG.md` (l.811) |
| X-5 | **Citas de muelle unificadas y ETA (S-5)** | Dueño de la agenda de sus puertas (V39/V45) | Solicita la cita de recogida; emite `TRUCK_ETA_UPDATED` | **Propuesta**; nada implementado | Dennis (D-04 del TMS) | TMS:`docs/architecture/ADR-025-warehouse-sync-v1-1.md` §7; EWM:`docs/tms-sync/DESIGN.md` §5 |
| X-6 | **Citas en el CD del cliente** | Citas de entrega (en curso, rama de empaques/EDI) | Citas de destino (V41) | **Riesgo de doble dueño** — decidir quién manda antes de mergear | Lia + Dennis — **propuesta** | EWM:`docs/DELIVERY_APPOINTMENTS.md` (rama en curso); TMS:`docs/roadmap/2026-10-enterprise/01_SINCRONIZACION_EWM_TMS.md` S-5 |
| X-7 | **Anulación de pedido en las dos direcciones (S-3)** | `TMS_ORDCX` sale; `ORDER_CANCELLED` entra y **sólo alerta** (no anula solo) | Recibe y emite | Hecho; falta IT de un short-close real | — | EWM:`docs/tms-sync/DESIGN.md` §4 |
| X-8 | **Reconciliación (S-4)** | Apagada de fábrica por dos llaves | Expone `updatedSince` | Hecho; encender tras una pasada manual revisada | Operación del cliente | EWM:`docs/tms-sync/DESPLIEGUE.md` §4 |
| X-9 | **¿Quién es dueño del pedido?** El ERP alimenta a los dos por separado | — | — | Abierto | Lia + Dennis — **propuesta** | TMS:`docs/roadmap/2026-10-enterprise/00_ESTADO_ACTUAL.md` §3 (hueco 1) |
| X-10 | **Sincronía de maestros** (clientes, transportistas, vehículos, locales) | Sólo materiales viajan (`TMS_MATP`) | Maestros propios | Abierto | — **propuesta** | TMS:`00_ESTADO_ACTUAL.md` §3 (hueco 2) |
| X-11 | **Documentos fiscales recibidos** (guía de remisión, factura) | No los emite | Los recibe del ERP (V69, ADR-028) y nunca los emite | Hecho / fuera de alcance emitir | Edu | [Edu 2026-10-18] (a); TMS:`docs/architecture/ADR-028-logistics-documents-received-international.md` |
| X-12 | **Ambientes** | QAS desplegado, base compartida con demos; sin PRD | QAS desplegado; sin PRD | PRD inexistente en los dos | Dennis | [Edu 2026-10-18] (e); EWM:`docs/release/EWM_PRODUCTION_READINESS.md`; TMS:`docs/environments/QAS.md` |

---

## 8 · Riesgos y deuda técnica

| # | Riesgo / deuda | Impacto | Mitigación | Fuente |
|---|---|---|---|---|
| R-1 | **QAS-H1**: el canal de despliegue podía no llegar a la base (estuvo en V35 dos promociones) | Probar contra un esquema viejo | H0-1 | `TMS_QAS_RUNTIME_CERTIFICATION.md` §QAS-H1 |
| R-2 | **Defaults fiscales para todo el despliegue** (no por empresa) | Un tenant hereda los porcentajes de otro | Revisar con H0-5 / H1-2 | `MORNING_REPORT.md` §7 R-6 |
| R-3 | **27 CHECK borrados sin `IF EXISTS`** | Una migración puede fallar en una base que difiere | H0-8 | `MORNING_REPORT.md` §7 R-3 |
| R-4 | **Validación de comprobantes apagada** | Un comprobante inválido se concilia igual | H2-7 | `MORNING_REPORT.md` §7 R-7 |
| R-5 | **Supuestos de demo** (perfiles físicos, Δh, 1 % de retención) leídos como definitivos | Prometer un número que no está medido | Etiquetas VIVO / ROADMAP del guion | `PLAPASA_TMS_DEMO.md` §0 «Qué NO decir» |
| R-6 | **Volumen de revisión** de los lotes nocturnos | Errores que pasan sin mirar | Revisión por fase | `MORNING_REPORT.md` §7 R-5 |
| R-7 | **Techos de escala**: tablero en una llamada, excepciones sin paginar, KPI a 92 días, importación de 2 MB | Cuello con 10.000 pedidos/día | Medir antes de PRD — **propuesta** | `KNOWN_LIMITATIONS.md` §7; `CLAUDE.md` (meta de escala) |
| R-8 | **Documentación que se quedó atrás** (ver §9.1) | Decisiones con datos viejos | Corregir en el siguiente PR | §9.1 |
| R-9 | **El EWM puede pisar las versiones de sus migraciones** (dos ramas con V54–V57) | Un despliegue conjunto falla | Lo resuelve el EWM antes de mergear | ver §7, X-2 |

---

## 9 · Cómo se mantiene este documento

- **Quién:** el Dev del producto (TMS) lo actualiza; Choco revisa que cada renglón diga
  el valor en palabras de negocio; Lia, el orden de H1 y H2; Dennis aprueba H0.
- **Cuándo:** al cerrar cada fase que mueva un renglón (en el mismo PR), al cambiar una
  fila de `docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md` y antes de cada reunión
  comercial. Lo que pasa a hecho se borra de acá y queda en el mapa de capacidades.
- **Regla:** ningún renglón sin fuente; lo que no tiene documento va como «propuesta».
  La §7 se edita **en los dos repositorios a la vez**.

### 9.1 Contradicciones encontradas al escribir esto (2026-10-18)

| # | Qué dice cada lado | Fuente |
|---|---|---|
| C-1 | **Re-apilado por vehículo**: `MORNING_REPORT.md` §5/§7, `CERTIFICATION.md` §7 y la fila 41 del mapa lo dan por diferido; `DECISIONS_LOG.md` (l.777) y ADR-022 lo dan por hecho (`1f99fb3`) | los citados |
| C-2 | **Geometría en pantalla**: los mismos documentos dicen «ciclo 2»; `Occupancy.tsx` existe y el runbook dice «geometría en pantalla» | `frontend/tms-web/src/shared/geometry/Occupancy.tsx` |
| C-3 | **Barridos de finanzas**: `MORNING_REPORT.md` R-1 dice encendidos por defecto; `DECISIONS_LOG.md` (l.795), apagados | `0570462` |
| C-4 | **Edición fiscal**: el informe y el mapa dicen «ciclo 2»; ADR-027 §2 y `DECISIONS_LOG.md` (l.876) dicen que existe `PUT /settlement/fiscal-settings` (los términos de pago sí faltan) | los citados |
| C-5 | **Migraciones**: `00_ESTADO_ACTUAL.md` dice V58; la carpeta llega a **V69**; `QAS.md` dice «V1…V49»; QAS-H1, V35 | `backend/tms-api/src/main/resources/db/migration` |
| C-6 | **Pruebas**: 2114/189 (`00_ESTADO_ACTUAL.md`), 2324/308 (mapa), 2613/394 (`CERTIFICATION.md`) | los citados |
| C-7 | **Nombre del puerto**: `CpeValidationPort` (ADR-024, `MORNING_REPORT.md`) vs `FiscalDocumentValidationPort` (ADR-027) | los citados |
| C-8 | **Emisión de GRE**: `04_ROADMAP_ENTERPRISE.md` ítem 3 la planea con «ADR-015 abierta»; ADR-028 dice que el TMS nunca emite, y hoy se confirmó fuera de alcance | [Edu 2026-10-18] (a) |
| C-9 | **`docs/product/ROADMAP_NEXT.md`** dice «integración con el EWM: diferida» (mergeada el 2026-09-28) y pone como futuros el costo por pedido y Storage (hechos: V68, ADR-020) | `docs/product/ROADMAP_NEXT.md` |
| C-10 | **`docs/product/KNOWN_LIMITATIONS.md`** está fechado en V35: «sin estado entregado», «sin portal de transportista», «sin ETA» ya no son ciertos (ADR-009, ADR-018, ADR-011) | `KNOWN_LIMITATIONS.md` |
| C-11 | **KPI de liquidación**: la fila 23 del mapa dice «abierto»; las filas 43 y 47 los dan por entregados | mapa |
| C-12 | **Diálogo**: `CLAUDE.md` ya dice `promptDialog`; `MORNING_REPORT.md` §7 sigue listando el cambio de `confirmWithReason` como pendiente | los citados |
| C-13 | **Hay dos D-01 y dos D-04** en la suite: los del TMS (dinero; citas unificadas) y los de PLAPASA en el EWM (bono; `stocked_at`). Acá siempre «TMS D-xx» | `DECISIONES.md`; EWM `docs/plapasa/DECISIONS.md` |
| C-14 | **QAS desplegado** según el dueño del negocio; ningún documento del repo observó todavía un despliegue (`QAS.md`, `PROMOTION.md`) | [Edu 2026-10-18] (e) |
