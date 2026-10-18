# 03 · Finanzas de fletes: conciliación, provisiones, impuestos y pagos

> Hoy: «el TMS valida, el ERP paga» (V46). Este plan **mantiene** que el TMS no mueve
> dinero ni guarda cuentas bancarias (decisión D-01), y agrega todo lo que una
> empresa necesita para cerrar el mes con los fletes: provisión, impuestos peruanos,
> notas de crédito, estado de pago y costo por pedido/cliente.

## F-1 · Endurecer la conciliación (fase 50)

- **Tolerancias**: endpoint + pantalla para `tolerance_policy` (empresa y transportista,
  absoluta o %). El permiso `settlement.tolerance:manage` ya está sembrado.
- **Varias líneas por viaje** con **código de cargo** (`charge_code` = componente de
  tarifa: BASE, FUEL_SURCHARGE, WAITING_TIME, TOLL, STOP_OFF, OTHER_ACCESSORIAL, PENALTY).
  El match compara **por componente** contra el desglose del costo estimado/real; la 2.ª
  línea del mismo viaje ya no es `DUPLICATE_INVOICE` (sólo lo es la misma línea repetida).
- **Discrepancia ↔ línea**: `freight_discrepancy.invoice_line_id` se llena.
- **Contra qué se compara**: el costo **real** si está cerrado; si no, el estimado. Se dice cuál.
- **Exportación CSV** (adaptador que falta) y campos contables en la exportación (ver F-2).
- KPIs de liquidación: ciclo de factura (recibida→aprobada), % con discrepancia, monto
  observado/ahorrado por transportista.

## F-2 · Provisión de fletes (accrual) (fase 55)

**Problema**: el camión salió hoy, la factura llega en 30 días; contabilidad necesita
reconocer el gasto **en el mes del servicio**.

- Tabla `freight_accrual` (una por viaje y moneda) con estados
  `OPEN → PARTIALLY_SETTLED → SETTLED` o `REVERSED`, y movimientos inmutables
  `freight_accrual_entry` (`ACCRUE`, `ADJUST`, `CONSUME`, `REVERSE`) — append-only por
  trigger, como `settlement_approval`.
- **Cuándo nace**: configurable por empresa (`accrual_trigger` = `DISPATCHED` |
  `DELIVERED`; por defecto `DISPATCHED`). Monto = costo estimado del viaje (o real si ya
  está cerrado). Un viaje sin costo calculable **no** inventa provisión: se lista en
  «Viajes sin provisión» con el motivo.
- **Ajuste**: si cambia el costo real antes de facturar → entrada `ADJUST` por la diferencia.
- **Consumo**: al **aprobar** la factura, `CONSUME` por lo facturado de ese viaje; la
  diferencia (provisión − factura) queda visible como **varianza**.
- **Reversa**: viaje anulado → `REVERSE`.
- **Mapeo contable** `gl_account_mapping` (empresa, concepto: FREIGHT_EXPENSE,
  FREIGHT_ACCRUAL, IGV_CREDIT, DETRACCION_PAYABLE, CARRIER_PAYABLE; cuenta, centro de costo
  opcional por ruta/origen). Sin mapeo la exportación **avisa** y no inventa cuentas.
- **Reporte de cierre**: provisión abierta al día X, antigüedad (0–30, 31–60, 61–90, >90),
  por transportista y moneda; exportación de asientos (JSON y CSV) por período:
  provisión del mes, reversas, consumo. **No hay libro mayor**: es un archivo para el ERP.

## F-3 · Impuestos peruanos y documentos (fase 60)

- Factura del transportista con desglose: `subtotal`, `igv_rate` (18 % por defecto,
  configurable), `igv_amount`, `total`; validación `subtotal + igv = total` con tolerancia
  de redondeo de 0,01.
- **CPE**: tipo (01 factura, 07 nota de crédito, 08 nota de débito), serie
  (`F###`/`E###`), correlativo, RUC emisor con **dígito verificador módulo 11**. Unicidad
  (empresa, RUC, tipo, serie, correlativo). **No** se valida contra SUNAT en línea (no hay
  credenciales; queda como puerto `CpeValidationPort` deshabilitado — EXTERNAL_DEPENDENCY).
- **Detracción (SPOT)**: código de bien/servicio (027 transporte de carga), % (4 % por
  defecto, configurable), umbral (S/ 400 por defecto, configurable), monto calculado,
  y número/fecha de constancia de depósito cuando el ERP la informa. Se calcula, no se paga.
- **Retención** (agente de retención): % configurable (3 % por defecto) cuando la empresa lo es.
- **Nota de crédito** (tipo 07) referenciando la factura: monto negativo efectivo; se
  concilia contra la factura original y reduce lo exportado. **Nota de débito** (08) suma.
- **Penalidades / descuentos**: línea con `charge_code = PENALTY` y monto negativo,
  con motivo y referencia (excepción del viaje, entrega fallida, demora). Requiere aprobación.
- Multimoneda: **sin FX**. Cada documento en su moneda; el desglose de impuestos sólo se
  exige en PEN (en USD se guarda si viene).

## F-4 · Cuentas por pagar: estado de pago (fase 65) — sin mover dinero (D-01)

- `carrier.payment_terms_days` (por defecto 30) → `due_date` de cada factura aprobada.
- Ciclo nuevo **después de `EXPORTED`**: `SCHEDULED` → `PARTIALLY_PAID` → `PAID`, o
  `ON_HOLD` (con motivo). El estado lo **informa el ERP**
  (`POST /integration/v1/payable-payments`, idempotente: referencia de pago, fecha, monto,
  moneda, constancia de detracción) o lo registra una persona con cuatro ojos.
- Reporte de antigüedad de cuentas por pagar y de vencimientos; tarjeta en la torre de control.
- **Portal del transportista**: «Mis facturas» (estado, vencimiento, pagado, detracción),
  sólo las suyas (RLS `tms.current_carrier_id()`).
- Webhooks nuevos: `INVOICE_APPROVED`, `PAYABLE_EXPORTED`, `PAYABLE_PAID`.

## F-5 · Costo por pedido y por cliente (fase 70)

- `trip_cost_allocation`: reparte el costo (real si está, si no estimado) del viaje entre
  sus pedidos por una **base configurable** por empresa: peso, volumen efectivo, piso,
  pallets, paradas o partes iguales. La base y la fecha viajan en cada fila; recalcular
  crea una versión nueva (no pisa).
- Lo que no tiene base (pedido sin peso con base = peso) **no** recibe un reparto
  inventado: se reparte entre los medibles y se cuenta el resto.
- Reporte «Costo por cliente / destino / ruta» y por kg / m³ / pedido. **Sin margen**:
  no hay tarifa de venta (queda en el roadmap).

## Lo que queda fuera (dicho)

Ejecutar pagos o archivos bancarios; libro mayor; validación SUNAT en línea; emisión de
facturas de venta; FX; tarifa de venta al cliente y margen. Ver `04_ROADMAP_ENTERPRISE.md`.
