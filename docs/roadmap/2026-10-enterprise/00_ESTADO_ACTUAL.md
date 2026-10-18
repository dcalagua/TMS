# 00 · Estado actual del TMS by EBIM (2026-10-18)

> Diagnóstico leído del código, las migraciones y el historial de git del TMS
> (`EBIM/TMS`, rama `dev` en `40de0a5`) y del EWM (`EBIM/IACLAUDE/WMS-by-EBIM`,
> rama `feature/plapasa-overnight` en `f1a0e48`). No se leyó ninguna base.
> Es el punto de partida del plan; las fases lo citan y **no lo vuelven a discutir**.

## 1 · Git y entornos

| Hecho | Detalle |
|---|---|
| `dev` local vs `origin/dev` | Local **4 commits adelante** (ADR-021, V58 ciclo de vida del tenant), sin push. `git fetch` no trajo nada nuevo |
| Ramas locales | Todas ya contenidas en `dev` (por patch-id o reaplicadas). Se pueden podar; **lo hace una persona**, no el pack |
| QAS | Indicios de Render + Supabase (us-east-1), pero **ningún documento verifica** a qué versión llegó Flyway. `origin/qas` 12 commits detrás de `dev` (le falta V58 y ADR-020) |
| PRD | **No existe** |
| CI | `.github/` sin workflows |
| Pruebas | Backend 2114/0 (última corrida completa con PostgreSQL 17); front 189 en 29 archivos (sep-28) |
| Migraciones | TMS V1–V58 (siguiente libre **V59**). EWM V1–V55 (siguiente libre **V56**) |

## 2 · Módulos del TMS (backend `com.ebim.tms.*`)

appointments · audit · costing (flota propia) · fleet (transportistas, tipos, vehículos,
choferes, disponibilidad, asignación de trabajo) · iam (+ provisioning, entitlements,
lifecycle) · integration (API de socios, webhooks, clientes, despacho del almacén) ·
masterdata (locales, zonas, frecuencias, rutas) · notification · orders (pedidos,
retenciones, programación, documentos) · planning (corridas, viajes, licitación en
cascada, torre de control, KPIs, portal del transportista) · rates (tarifas, costo del
viaje) · routing (estimador geodésico + caché) · settlement (conciliación de facturas) ·
tracking (puerto, sin proveedor).

## 3 · Integración EWM ⇄ TMS (v1, mergeada en los dos lados el 2026-09-28)

**Funciona:** TMS → webhook firmado HMAC → EWM tira el plan (`GET /integration/v1/shipments/{n}`);
EWM → TMS `dispatch-confirmations` (SLS con lote y LPN, idempotente por
`ewm-dispatch:<SLS>:r<rev>`) y `warehouse-milestones`. 9 escenarios E2E locales
(`TMS/scripts/e2e/tms-ewm/run.sh`, acepta `EWM_ROOT`).

**Huecos:**

1. El ERP alimenta a los dos por separado; nadie es dueño del pedido.
2. Sin sincronía de maestros (materiales, clientes, transportistas, vehículos, locales).
3. El EWM **descarta** peso, volumen, pallets y ventanas horarias del plan
   (`TmsShipmentPlanParser.java:27-56` no los lee).
4. El EWM **ignora** `DELIVERY_RESULT_RECORDED` (la POD no vuelve al almacén).
5. Anulación de **pedido** no cruza en ninguna dirección (sólo la del viaje).
6. Citas de muelle del TMS (V41) y del EWM (V39/V45) no se conocen.
7. Sin ETA hacia el almacén; el contrato sólo cubre salidas.
8. La placa del camión cae en `trailer_number` del EWM → falso `VEHICLE_MISMATCH` con tracto+remolque.
9. Sin pull de reconciliación (`updatedSince`) ni pantalla del conector en el EWM.
10. Documentación vieja: `WAREHOUSE_EXECUTION_V1.md` dice que `SHIPMENT_CHANGED` está «planned»
    (ya existe); `TMS_CONNECTOR_V1.md` §10 dice que los endpoints del TMS dan 404 (ya existen);
    `TMS_EWM_IMPLEMENTATION_REPORT.md` dice «nothing merged» (sí está).

## 4 · Capacidad de carga (apilables / anidables)

- Capacidad = suma de **tres** números: kg, m³, pallets (`CapacityLimits.accommodates`,
  `CapacityLoad`). Pallets tecleados a mano en la línea.
- Volumen de línea = `quantity × unitVolumeM3` (`OrderLineInput.java:28-35`).
- **No hay maestro de materiales** (el material es un texto en la línea).
- `vehicle_type` guarda `length_m/width_m/height_m` (V9) y **nadie los usa**.
- Sin bin-packing, sin piso, sin altura, sin `CostUnit` por metro lineal.
- Ejemplo PLAPASA: 1.000 vasos PLP-571 → TMS cree 1,93 m³; anidados ≈ 0,15 m³ (supuesto
  Δh≈1,2 cm). La pila máxima de piso del PLP-1601 (10 × 29 cm = 2,9 m) no entra en una caja de 2,5 m.
- El EWM (V54) modela sólo «unidades por pila»; difiere altura e incremento de anidado
  (`docs/STACKING.md` §8).

## 5 · Finanzas de fletes («el TMS valida, el ERP paga», V46)

| Capacidad | Estado |
|---|---|
| Tarifas (V30/V39): 12 componentes, alcance CARRIER/ORIGIN/LANE/ROUTE, combustible % fijo | Hecho. Sin tramos, sin índice de combustible, sin tarifa de venta |
| Costo estimado vs real del viaje | Hecho. Real tecleado a mano |
| Flota propia (V48) | Sólo estimado |
| Factura del transportista, match, discrepancias, aprobación con cuatro ojos (V46) | Hecho v1 |
| Tolerancias | Permiso sembrado, **sin endpoint ni UI** → siempre `Tolerance.NONE` |
| Varias líneas por viaje | La 2.ª se marca `DUPLICATE_INVOICE` (`FreightMatcher`) |
| Discrepancia ↔ línea | `invoice_line_id` siempre NULL |
| Exportación | JSON mínimo; CSV declarado sin adaptador; sin cuenta contable, centro de costo ni impuestos |
| **Provisión (accrual)** | **No existe** |
| **Pagos** | **No existe** (excluido a propósito en V46:13) |
| **IGV / detracción / retención / CPE SUNAT** | **No existe** (total sin desglose) |
| **Notas de crédito / penalidades / reclamos** | **No existe** (`total_amount >= 0`) |
| **Costo por pedido / cliente** | **No existe** (deuda D10) |
| GRE transportista | Sólo referencia del ERP (V55); no se emite |

## 6 · Brechas enterprise (fuera de esta noche)

Optimización de rutas (diferida por decisión), multi-tramo/multimodal/consolidación,
proveedor GPS real, emisión de GRE transportista electrónica, portal del cliente,
reclamos de daño, huella de carbono, carga 3D, scorecards de transportista, PRD.
Ver `04_ROADMAP_ENTERPRISE.md`.
