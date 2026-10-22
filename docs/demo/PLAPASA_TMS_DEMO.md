# Demo PLAPASA · capítulo Transporte (TMS by EBIM)

> Para Edu y Dennis. El capítulo de transporte de la demo de PLAPASA (plásticos, Guayaquil, USD,
> America/Guayaquil). Va **inmediatamente después** del capítulo «Carga y despacho» del EWM
> (`WMS-by-EBIM/demo/plapasa/GUION_FLUJO_COMPLETO_PLAPASA.txt`, pasos 8–9, y el capítulo 7 de
> `docs/plapasa/DEMO_LUNES.md` del EWM). Allí se vio salir un camión del almacén; acá se cuenta lo
> mismo desde el lado del transporte: **qué camión hacía falta, cuánto ocupa de verdad, qué cuesta,
> qué se le retiene al transportista y qué pasó en la entrega.**
>
> Datos: `supabase/seeds/plapasa_demo_seed.sql` + los pedidos del ERP de
> `scripts/demo/plapasa/orders.json` (+ la guía de remisión opcional `remission_guide.json`). Cómo
> cargarlo en QAS: [`PLAPASA_TMS_RUNBOOK.md`](PLAPASA_TMS_RUNBOOK.md).
> **Todos los números de abajo salen de `PlapasaDemoSeedIntegrationTest`** (Testcontainers, Flyway
> V1–V69 de la rama integrada, seed aplicado dos veces, pedidos, guía y factura por la API real,
> planificación con re-apilado por vehículo, despacho y cuentas por pagar por los servicios reales).
> Si en pantalla aparece otro número, algo del entorno es distinto: ver §4.

---

## 0 · Qué está vivo y qué no (leer antes de prometer)

| Etiqueta | Qué significa | Capítulos |
|---|---|---|
| `VIVO TMS` | Sólo necesita el TMS desplegado (rama integrada, V69) y el seed cargado | b, f, y el lado TMS de c, d, e |
| `REQUIERE EWM v1.1` | El EWM desplegado con V56–V58 y el conector TMS configurado (runbook §6–§7). Sin eso: perfiles por el respaldo SQL y el despacho por la persona (modo HYBRID) | a, el lado EWM de c, d, e |
| `OPCIONAL` | Guía de remisión SRI 06 recibida del ERP | c.6 |
| `ROADMAP` | No existe. Se nombra como siguiente paso, nunca se muestra | g |

**Qué NO decir:** que el TMS está «conectado» con el ERP de PLAPASA (los pedidos, la guía y la factura
los manda un script con el formato del ERP); que el almacén ya apila con el perfil (lo declara y lo
manda); que el TMS **emite** o **valida con el SRI** la guía o la factura (las **recibe** y valida su
forma: número, RUC, desglose; la validación electrónica dice «dependencia externa»); que la retención
del 1 % es definitiva (ADR-027: confirmar con el contador por la resolución NAC-DGERCGC26-00000009).

---

## 1 · El elenco (todo «(demo)», nada real)

| Qué | Valor | Igual que en el EWM |
|---|---|---|
| Empresa TMS | `QAS-PLAPASA` · **PLAPASA (demo)** · país EC (régimen fiscal de Ecuador por defecto) · USD · America/Guayaquil · modo de despacho **HYBRID** | — |
| Origen | `GYE-CD` · Planta de inyección y soplado · Km 14,5 Vía a Daule (código de almacén `GYE-CD`) | sí |
| Clientes | `CLI-SUP-01` Supermercados Costa Azul (Kennedy Norte) · `CLI-FER-01` Ferretería El Constructor (centro) · `CLI-AGR-01` Agroexportadora Río Babahoyo (Babahoyo) · `CLI-UIO-01` Distribuidora Andina del Norte (Quito) | sí (códigos, nombres, direcciones) |
| Rutas | `RUTA-GYE-NORTE` (supermercado) · `RUTA-GYE-SUR` (ferretería) · `RUTA-LOS-RIOS` (agro) · `RUTA-SIERRA-NTE` (Quito) · lunes a sábado sin corte | el EWM usa `RUTA-GYE-NORTE` |
| Transportista | `TRLIT` · Transportes del Litoral S.A. (demo) · RUC **0990000034001** (válido, módulo 11) · pago a 30 días | código sí, RUC no (el del EWM no pasa el control: §10) |
| Vehículos | `CAM-01` **GBA-1234** camión 10 t (6,2 × 2,4 × 2,4) · `CAM-02` **GBC-5678** furgón 5 t (6,0 × 2,4 × 2,4) · `CAM-03` GSC-2468 camioneta NHR (4,3 × 2,0 × 2,0) · `CAM-04` GSD-1357 furgón **caja baja 2,20 m** (6,0 × 2,4 × 2,2) | CAM-01 y CAM-02 sí |
| Tarifa | `TRLIT-2026-USD`: USD 45 por viaje + 0,85/km + 5/parada + **6,50 por metro lineal** · mínimo 60 | — |
| Personas | `admin@plapasa.demo` (administración) · `planner@plapasa.demo` (planificación) · `finanzas@plapasa.demo` (aprueba facturas: cuatro ojos) · `transportista.trlit@plapasa.demo` (portal) | mismo correo de admin que el EWM, otra cuenta |

Los pedidos del ERP (`orders.json`), con la **misma clave que el EWM**: `externalSource = MANUAL` +
el N° del cliente del EWM. Es `MANUAL` porque así los tiene el EWM de la demo (los creó su cargador
sin sistema de origen); el plan y el despacho se encuentran **byte a byte** por esa clave. Las cifras
«ocupa» son a la altura de referencia de la empresa (2,40 m); cada camión las **re-apila** a su alto.

| Pedido ERP | Cliente | Fecha | Líneas | Declarado | **Ocupa** | Piso | Pila más alta |
|---|---|---|---|---|---|---|---|
| `SO-2026-10040` | Ferretería | 20/10 | 30 Utility Box · 24 tachos 72 l · **40 sillas Costabela** · 60 baldes 10 l | 20,76 m³ | **4,58 m³** | 2,33 m² | 2,35 m (25 sillas encajadas) |
| `SO-2026-10034` | Agroexportadora | 20/10 | 200 gavetas 1601 · 120 gavetas 1608 · 80 gavetas 1623 | 25,82 m³ | 25,81 m³ | **11,85 m²** | 2,32 m (8 gavetas) |
| `SO-2026-10041` | Quito | 20/10 | 12 cómodas · 36 sillas Bambini · 24 bancos | 6,71 m³ | 4,69 m³ (2 líneas sin medir) | 0,11 m² | 1,98 m |
| `SO-2026-10045` | Supermercado | 21/10 | **5.000 vasos PLP-571** | 9,50 m³ | **0,75 m³** · 50 pilas | 0,55 m² | 1,363 m |
| `SO-2026-10046` | Supermercado | 21/10 | 4.000 vasos + 120 baldes eco 15 l | 10,73 m³ | **0,98 m³** · 44 pilas | 0,81 m² | 1,363 m |
| `SO-2026-10021` | Supermercado | 09/10 | (ya despachado por el EWM a mano) | 3,18 m³ | 0,63 m³ | — | — |

`SO-2026-10045/46` **no existen en el EWM**: son la campaña de vasos que sólo se *planifica*
(borrador del 21/10). `SO-2026-10021` queda **no liberado**: es historia (salió el 09/10 en
`CRG-000001`/`SLS-000001` del EWM). Los pesos unitarios son SUPUESTO de demo (el ERP los conoce; el
almacén no los publica).

---

## 2 · Guion (≈ 16 min; versión corta de 7 min en §7)

Entrar como `planner@plapasa.demo`; `admin@plapasa.demo` y `finanzas@plapasa.demo` en otras dos
ventanas (privadas) para finanzas. Pestaña aparte con el EWM (`https://ewm.ebim.pe/t/plapasa`) en
**Integraciones › TMS**.

**Frase puente desde el EWM:** «Acaban de ver salir un camión del almacén. La pregunta que el almacén
no contesta es *qué camión hacía falta*. Eso lo decide el transporte, y para decidirlo bien necesita
saber algo que sólo sabe el almacén: cómo se apila cada producto.»

### (a) El perfil del material llega del almacén — 2 min · `REQUIERE EWM v1.1`

1. **EWM** · Maestros › Materiales › `PLP-571`: «Modo de apilado: anidable», incremento **1,2 cm**,
   100 por pila. «Esto lo midió el almacén; es su dato.»
2. **TMS** · Maestros › **Perfiles de material** · buscar `PLP-571`. Abrir el cajón:
   0,105 × 0,105 × 0,175 m · **NESTABLE** · incremento **0,0120 m** · pila máx. **100** · origen
   **`EWM_EBIM`** · «Actualizado en origen» y «Sincronizado».
   - «La pantalla es de sólo lectura y lo dice: el dueño del dato es el almacén. Si está mal, se
     corrige allá y viaja solo.»
   - `PLP-700` Silla Costabela: **NESTABLE, 6 cm**. «Una silla monobloque se encaja en la de abajo:
     si la declaráramos "apilable", diez sillas medirían 9 m.»
   - `PLP-1601` Gaveta Agro Alta: **STACKABLE**, 10 por pila, **150 kg** encima.
3. Si el origen dice **`PLAPASA_DEMO_SEED`** en vez de `EWM_EBIM`: se cargó el respaldo (§4). Decir la
   verdad: «estos los cargamos nosotros con los mismos valores del almacén; en producción llegan
   solos».

### (b) Los pedidos del ERP y el planificador — 5 min · `VIVO TMS`

1. **Operación › Pedidos.** Seis pedidos con el número del ERP (`SO-2026-…`) al lado del nuestro
   (`OT-0000000n`), y la columna **«Ocupa»**: `SO-2026-10045` dice **50 pilas · 0,55 m² de piso · alto
   1,36 m** y **0,75 m³ frente a 9,50 declarados**.
   Abrir el pedido: sección **«Ocupa»** — volumen de planificación 0,75 m³, piso, pila más alta, alto de
   referencia 2,40 m; por línea, «Apilado» (perfil del almacén), «Ocupa» y «Volumen».
   - «Ayer el TMS hacía esta cuenta: cantidad por volumen unitario. 1.000 vasos = 1,90 m³.
     Pero un vaso entra en el otro: **1.000 vasos son 10 pilas de 1,363 m, 0,15 m³ y 0,11 m² de
     piso.**» (Catálogo: 1,93 m³; el TMS guarda el volumen unitario con 4 decimales y muestra 1,90.)
   - `SO-2026-10041`: «2 líneas sin medir» — cómodas y bancos, que el almacén no declara. «Lo que no se
     sabe se cuenta y se muestra; no se inventa.»
2. **Operación › Planificación** · origen `GYE-CD` · fecha **21/10/2026** · «Nuevo plan». Las tarjetas
   de pedidos elegibles dicen **«140 kg · 0,75 m³»** (`SO-2026-10045`) y **«202 kg · 0,98 m³»**
   (`SO-2026-10046`): lo que **ocupan**, no lo declarado.
3. **Dos pedidos, una camioneta.** Crear un viaje con **CAM-03 (camioneta NHR, 17,2 m³)**. Asignar
   `SO-2026-10045` y después `SO-2026-10046`. Los dos entran. En la tarjeta del viaje, las barras
   **Peso · Vol. · Pal. · Piso · Alto**: volumen **1,73 m³ de 17,2** (10 %), piso **1,36 de 7,31 m²**,
   alto **1,363 de 2,00 m**.
   - «Con la cuenta de ayer, estos dos pedidos sumaban **20,23 m³**: no entraban en una camioneta de
     17,2. Hacían falta dos. Hoy entran en una, y sobra lugar para la ruta del día.»
   - Este plan **se deja en borrador** (estos dos pedidos no existen en el EWM de la demo; confirmarlo
     le mandaría al almacén un plan con pedidos que no conoce). «Cancelar plan» o dejarlo.
4. **La caja baja: el TMS re-apila para ESE camión.** Otro plan: origen `GYE-CD` · **20/10/2026**.
   Crear un viaje con **CAM-04 (GSD-1357, furgón caja baja, 2,20 m)**.
   - Asignar primero `SO-2026-10034` (gavetas del agro). **Rechazo**, con la causa:
     > *Order OT-00000002 does not fit trip 1: floor area 13.093 m2 exceeds the capacity of 12.24 m2.*

     «A 2,40 m las gavetas van de a 8 (2,32 m). Para esta caja el sistema las re-apiló más bajas —7, 7
     y 6 por pila— y entonces necesitan **13,09 m² de piso**, y la caja tiene 12,24. No dice "no entra
     de alto": dice por qué de verdad no entra.»
   - Asignar `SO-2026-10040` (ferretería). **Entra.** Las sillas, que a la altura de referencia van de a
     25 (2,35 m), en esta caja van **de a 22 = 2,17 m**. Barra de **Alto: 2,17 de 2,20 m (98,6 %)** —
     es la dimensión que manda—; piso **2,33 de 12,24 m²** (las mismas dos pilas), volumen **4,58 de
     31,68 m³**, 285,8 kg de 5 t.
     «Un planificador habría dicho que 25 sillas no pasan por un techo de 2,20 y habría pedido otro
     camión. El sistema baja la pila y usa el que hay.» «Declarado: 20,76 m³. Ocupa: 4,58. Las 40 sillas
     solas bajan de 13,43 m³ a 1,51.»
5. **El camión justo.** Nuevo viaje con **CAM-01 (GBA-1234, camión 10 t)** · asignar `SO-2026-10034`.
   Entra: **alto 2,32 de 2,40 (96,7 %)**, **piso 11,85 de 12,65 m² (93,7 %)**, volumen 25,81 de 35,71
   (72 %), 864 kg de 10 t (8,6 %). «Este camión no se llena por peso ni por volumen: se llena por **alto
   y piso**. 55 pilas de gavetas. Un TMS que sólo mira kilos y m³ diría que sobra un 30 %.»
6. A cada viaje: **Ruta** (`RUTA-GYE-SUR` y `RUTA-LOS-RIOS`, «aplicar secuencia») y **Conductor**
   (`COND-02` y `COND-01`). **Confirmar el plan.** Nacen dos `VJ-…`.

### (c) Viaje, almacén, despacho y torre de control — 4 min · `REQUIERE EWM v1.1`

1. **EWM** · Integraciones › TMS › «Avisos recibidos»: el aviso `SHIPMENT_CONFIRMED` del viaje
   (firmado, recibido). «El TMS no le escribe al almacén: le avisa, y el almacén lee el plan.»
2. **EWM** · Salida › Cargas: aparece la carga `CRG-…` con número externo **`VJ-…`**, transportista
   TRLIT, tracto **GSD-1357** (CAM-04: el EWM no lo tiene dado de alta; lo conoce por el plan), ruta
   `RUTA-GYE-SUR`, y la pestaña **«Planificado por TMS»**: **285,8 kg, 4,58 m³** (lo que el TMS reservó
   en el camión: lo que ocupa, no los 20,76 declarados) y la ventana del cliente **08:00–12:00**.
   «Informa, no valida: el almacén carga lo que carga y lo compara.»
3. **EWM** · el operario sube la caja (`OBLPN-…` de `SO-2026-10040`, empacada antes: §5) a la carga
   → «Marcar lista para despachar» → **Despachos** · precinto → `SLS-…` + **601**.
4. **TMS** · Operación › **Viajes** › el `VJ-…` de la ferretería → tarjeta **«Despacho de almacén»**:
   - el SLS recibido, **revisión 1**, «Plan contra despachado»: **verificación OK** (30 / 24 / 40 / 60 UN
     contra lo planificado, placa GSD-1357, transportista TRLIT, almacén GYE-CD);
   - el viaje pasó solo a **En tránsito** y el pedido a **En ejecución**, atribuido a la credencial del
     almacén (modo HYBRID: «el primero que llega despacha»);
   - «Hitos del almacén»: carga iniciada / lista.
   - Si el almacén apiló distinto, aparece el chip **«Huella distinta a la planificada (informativa)»**:
     «el almacén apila sin la altura del camión; por eso es informativo, nunca bloquea».
5. **Torre de control**: el viaje en tránsito; ninguna alerta del despacho. (Si el SLS hubiera traído
   otra placa o un pedido de más, aparecería acá con su código.)
6. **Guía de remisión (SRI 06)** — `OPCIONAL` · 1 min. Antes de la reunión (o en vivo desde una
   terminal): `python3 scripts/demo/plapasa_tms_demo.py guide` manda por
   `POST /integration/v1/logistics-documents` la guía **001-001-000000123** del ERP (comprobante 06,
   clave de acceso de 49 dígitos, transportista TRLIT 0990000034001, placa **GSD-1357**, destinatario
   la ferretería con sus cuatro ítems). En el pedido `SO-2026-10040` y en el viaje (sección **Documentos**
   de cada uno) aparece **«Guía de remisión (SRI 06) 001-001-000000123»**; el
   transportista la ve en su portal y puede reportar su entrega con foto.
   «La guía la emite el ERP y la autoriza el SRI; el TMS la recibe, la ata al pedido y al camión, y
   sigue que el original vuelva firmado. No la emite.»
7. **Hoja de ruta** — `OPCIONAL` · 30 s. En el viaje, **«Imprimir hoja de ruta»**: una A4 con placa,
   transportista, conductor, la parada de la ferretería con su ventana 08:00–12:00, las cuatro líneas
   del pedido, la guía **001-001-000000123 (SRI 06)** y la casilla «Recibí conforme». Sin importes.
   «Es lo que el conductor lleva a la garita; el TMS la arma con lo que ya sabe del viaje.»
8. **Citas de muelle** — `OPCIONAL` · 1 min. Operación › **Citas de muelle** › Sitio **GYE-CD**: tres
   muelles (MUE-01..03, lunes a sábado 07:00–19:00, hora de la planta). Reservar MUE-01 para la carga
   del `VJ-…`; reservar el mismo muelle a la misma hora lo rechaza nombrando la cita que estorba.
   Con admin@, **«Puertas y muelles»** muestra el horario y los cierres; planner@ no ve ese botón.

### (d) La prueba de entrega, y cómo la ve el almacén — 2 min · POD `VIVO TMS` · en el EWM `REQUIERE EWM v1.1`

1. **TMS** · el viaje → parada 1 (`CLI-FER-01`) → **Llegó** → en el pedido, **Registrar entrega**:
   *Entregado* · receptor «Bodega Ferretería (demo)» · (foto/firma si el almacén de evidencias está
   activo). La hora no puede ser futura.
2. **EWM** · Salida › Despachos › `SLS-…` → pestaña **«Entrega (TMS)»**: entregado, receptor, hora.
   «El almacén sabe que llegó sin llamar a nadie. **No mueve stock**: el 601 ya lo bajó.» Con una
   entrega parcial o rechazada, el EWM ofrece **«Crear devolución»** (abre el RMA prellenado; no la
   crea).
3. Portal del transportista (opcional): `transportista.trlit@plapasa.demo` ve **sólo** los viajes de
   TRLIT que el almacén ya despachó.

### (e) La anulación cruza como alerta, no como orden — 1 min · `VIVO TMS` + `REQUIERE EWM v1.1`

1. **TMS** · Pedidos › `SO-2026-10041` (Quito, todavía sin planificar) → **Anular** · motivo «El
   cliente pospuso la compra (demo)».
2. **EWM** · Salida › Pedidos › `SO-2026-10041`: arriba, la alerta **«El TMS anuló el transporte de
   este pedido»**. «El almacén no anula solo (D-03). Si el pedido estuviera abierto y sin reserva, un
   supervisor lo anula desde acá; si no, la alerta dice por qué.» (En la demo está en borrador.)
3. **Al revés**, decirlo sin mostrarlo: «si el almacén anula un pedido del ERP, el TMS lo **retiene**
   —sale de planificación, y si ya estaba en un camión, bloquea su despacho y lo avisa la torre—, y
   decide una persona». En esta demo **no cruza**: los pedidos del EWM de la demo tienen origen
   `MANUAL`, y el EWM sólo avisa los que tienen clave de ERP (§10).

### (f) Finanzas del flete en Ecuador — 3 min · `VIVO TMS`

1. **Administración › Empresa › «Régimen fiscal»** (como `admin@plapasa.demo`): país fiscal **EC**,
   régimen **Ecuador**, moneda fiscal **USD**, **IVA 15 %**, **flete 0 %**, tasas admitidas 0 / 5 / 15,
   identificación del emisor **RUC (13 dígitos)**, comprobantes SRI **01 / 04 / 05**. Retenciones:
   **retención en la fuente del IR, código 310, 1 % sobre el subtotal — activa**; retención de IVA 70 %
   — inactiva (sólo para agentes designados). «Nada de esto es código: es configuración de la empresa,
   y cada porcentaje lo confirma su contador.»
2. **Costo del viaje** (viaje de la ferretería → Costo → «Estimar»): **USD 68,73** = 45,00 por viaje +
   **16,30** (19,2 km × 0,85) + **7,43** (1,14 m lineales × 6,50). «Se cobra lo que el camión gasta de
   **largo**: 2,33 m² de piso sobre 2,04 m de ancho útil.» Viaje del agro: **USD 147,44** = 45,00 +
   64,69 (76,1 km) + 37,75 (5,81 m lineales).
3. **La factura de TRLIT** (Operación › Auditoría de flete › Nueva, como `admin@plapasa.demo`):

   | Campo | Valor |
   |---|---|
   | Comprobante | Factura **SRI 01** · número `001-001-789` → el sistema lo normaliza a **001-001-000000789** |
   | Emisor | RUC **0990000034001** (el del transportista: si no coincide, 422) |
   | Fecha · moneda | 20/10/2026 · **USD** |
   | Línea | Flete GYE-CD → Ferretería, viaje `VJ-…` · **68,73** |
   | Subtotal · IVA · total | **68,73** · **0 %** (flete) = 0,00 · **68,73** |
   | Retención IR 310 (1 % del subtotal) | **0,69** (calculada al recibir; un exento se pone en 0 con motivo) |
   | Validación electrónica | «No disponible (dependencia externa)»: el SRI no se consulta |

   - **Conciliar**: *MATCHED* — se compara por el subtotal contra la estimación (68,73).
   - **Aprobar** con `admin@plapasa.demo`: **rechazado** («quien registró la factura no puede
     aprobarla»). Aprobar con **`finanzas@plapasa.demo`**: *APPROVED*. «Cuatro ojos, por construcción.»
     Al aprobarse, la factura **consume la provisión** del viaje si la había (punto 4).
   - **Exportar** a contabilidad (JSON/CSV con la cuenta de cada concepto y la retención con la suya).
   - **Cuentas por pagar**: neto **68,73** − retención **0,69** = **a pagar 68,04**, saldo abierto
     **68,04**, vence a 30 días. «A TRLIT se le paga 68,04; los 0,69 PLAPASA los declara al SRI. El TMS
     no mueve dinero: registra el pago que informa el ERP.»
4. **Provisiones**: el viaje despachado **nace provisionado** por su costo estimado (disparador «al
   despachar»). Mostrar «antigüedad» y «Provisionar ahora». (Requiere el barrido encendido o el botón:
   runbook §11.)
5. **Costo para servir**: el costo de cada viaje se reparte entre sus pedidos **por volumen efectivo**
   (configuración de la empresa) y se reporta por cliente, destino y ruta. Con los dos viajes del
   20/10: ferretería ≈ **USD 15,0 por m³ ocupado** (0,24 USD/kg); agroexportadora ≈ **USD 5,7 por m³**
   (0,17 USD/kg). «La ferretería del centro cuesta casi tres veces más por m³ que el agro de Babahoyo,
   aunque esté más cerca: va un camión casi vacío.» (Cifras sobre el costo **estimado**; con el real,
   cambian. No hay margen: no existe tarifa de venta, y la pantalla lo dice.)

### (g) Lo que sigue — 1 min · `ROADMAP`

- **Emitir** la guía de remisión electrónica (hoy el TMS la recibe del ERP y la sigue; emitirla y
  enviarla al SRI está fuera de alcance por decisión).
- **GPS**: el contrato de posiciones existe (ADR-007); falta el adaptador del proveedor que use TRLIT.
- **Optimización de rutas** (secuencia y asignación automática con ventanas): diferida por decisión;
  hoy la heurística agrupa por corredor y llena el vehículo más grande libre (y prueba los demás).
- **Régimen de cada transportista** (RIMPE, contribuyente especial) como dato maestro; hoy es un
  ajuste con motivo en la factura.
- **Citas de muelle unificadas** con el almacén (D-04): propuesta, sin implementar.

**Cierre:** «El inventario cuadra por construcción en el almacén; el camión cuadra por construcción
en el transporte. Y lo que se dicen el uno al otro son referencias de negocio: el número del pedido,
la placa, el código del almacén. Nada más.»

---

## 3 · Números para tener a mano

| Dato | Valor |
|---|---|
| 1.000 vasos PLP-571 | 10 pilas · 1,363 m · 0,1503 m³ · 0,11 m² · declarado 1,90 m³ (catálogo 1,93) |
| SO-2026-10045 + 10046 en CAM-03 | 1,73 m³ de 17,2 · piso 1,36 de 7,31 m² · alto 1,363 de 2,00 · declarado 20,23 m³ (no entraba) |
| SO-2026-10034 en CAM-04 (caja baja) | rechazo: **floor area 13.093 m2 exceeds 12.24 m2** (re-apiladas a 7/7/6) |
| SO-2026-10040 en CAM-04 | **re-apilado: 22 sillas = 2,17 m de 2,20 (98,6 %)** · 2,33 de 12,24 m² · 4,58 de 31,68 m³ · 285,8 kg |
| SO-2026-10034 en CAM-01 | alto 2,32 de 2,40 (96,7 %) · **11,85 de 12,65 m² (93,7 %)** · 25,81 de 35,71 m³ · 864 kg |
| Plan que lee el EWM | `VJ-…` · TRLIT · GSD-1357 · RUTA-GYE-SUR · SO-2026-10040 (MANUAL) · 285,8 kg · 4,58 m³ · 08:00–12:00 |
| SLS de la ferretería | APPLIED · verificación OK · viaje en tránsito |
| Costo | ferretería **USD 68,73** · agro **USD 147,44** |
| Factura TRLIT 001-001-000000789 | 68,73 + IVA 0 % · retención IR 310 1 % = **0,69** · **a pagar 68,04** |
| Guía SRI 06 | 001-001-000000123 · clave 2010202606099999999900110010010000001231234567811 |
| RUC TRLIT | 0990000034001 (09-9-000003 · verificador 4 · 001) |
| Muelles de GYE-CD | MUE-01, MUE-02 (60 min) · MUE-03 (90 min) · lunes a sábado 07:00–19:00 · domingo cerrado |

---

## 4 · Si algo falla en vivo

| Síntoma | Qué hacer | Qué decir |
|---|---|---|
| Perfiles de material vacíos o con otro origen | Correr el respaldo SQL **antes** de mandar pedidos (runbook §8). Si los pedidos ya entraron sin perfil, no se re-copian: en un ensayo, anular y volver a crear | «Este dato lo manda el almacén; hoy lo cargamos nosotros con sus mismos valores» |
| «Ocupa» del pedido muestra 9,50 m³ / «sin perfil» | Los pedidos entraron antes que los perfiles (snapshot). Ídem fila anterior | — |
| `SO-2026-…` no aparece en el plan del 20/10 | La fecha pasó: la integración no libera pedidos vencidos (CUTOFF_MISSED). `plapasa_tms_demo.py orders --service-date <hoy o mañana>` antes de planificarlos | «La programación respeta los cortes: un pedido tarde no se libera solo» |
| El EWM no crea la carga | EWM › Integraciones › TMS › Avisos: si no llegó, «Sincronizar» con el número `VJ-…`; si llegó con error de firma, revisar el secreto (runbook §7). Plan B: despachar el viaje a mano en el TMS (modo HYBRID) | «En modo híbrido, el primero que despacha manda y el otro concilia» |
| El SLS llega pero el viaje no se mueve (`UNAPPLIED`) | Torre de control → el aviso dice la causa | Leer el motivo en voz alta |
| `VEHICLE_MISMATCH` | El muelle del EWM tecleó otra placa como tracto (el plan dice GSD-1357) | «El despacho se registra igual: un hecho no se rechaza, se marca» |
| No deja registrar la entrega | Falta «Llegó» en la parada, o la hora es futura | — |
| Factura rechazada (422 emisor) | El RUC del emisor no es el de TRLIT (0990000034001), o tiene mal el verificador | «El sistema valida el RUC ecuatoriano por su dígito: no se registra un comprobante con un RUC mal escrito» |
| «Aprobar» da conflicto | Lo intenta quien la registró: aprobar con `finanzas@plapasa.demo` | «Cuatro ojos: quien registra no aprueba» |
| La factura no concilia (no MATCHED) | No se estimó el costo del viaje antes: «Estimar» y volver a conciliar | — |
| Errores del servidor en inglés | Es el texto del servidor; traducirlo en voz alta | — |

---

## 5 · Preparación (la víspera y 30 min antes)

**La víspera**

- [ ] Runbook completo en QAS (§1–§10), incluido el smoke.
- [ ] `python3 scripts/demo/plapasa_tms_demo.py check` sin faltantes (perfiles `EWM_EBIM` o el respaldo).
- [ ] Pedidos enviados (`… orders`): seis `CREATED`; una segunda corrida da seis `UNCHANGED`.
- [ ] (Opcional) Guía enviada (`… guide`): `CREATED`, 1 pedido enlazado.
- [ ] En el EWM, `SO-2026-10040` reservado, asignado, con ola, pickeado y **empacado** (OBLPN cerrado
      «listo para carga»), para que en vivo sólo se suba a la carga que crea el TMS.
- [ ] Ensayar (b)–(f) una vez y **reconstruir** (§6) si se confirmó algún plan.

**30 min antes**

- [ ] Backend y frontend del TMS despiertos (Render tarda ~1 min si estuvo inactivo).
- [ ] `planner@`, `admin@` y `finanzas@plapasa.demo` dentro, empresa **PLAPASA (demo)** seleccionada.
- [ ] Planificación sin planes del 20/10 ni del 21/10 (si hay de un ensayo: cancelarlos).
- [ ] Pedidos: `SO-2026-10040/34/41/45/46` **Listos para planificar**, `SO-2026-10021` **No listo**.
- [ ] Administración › Empresa › «Régimen fiscal»: Ecuador, IR 310 1 % activa.
- [ ] EWM en Integraciones › TMS: el conector activo y la última respuesta del TMS con hora de hoy.
- [ ] La fecha de servicio sigue siendo futura (si la demo se movió: `--service-date`).

## 6 · Volver a empezar (entre ensayos)

Cancelar los planes de ensayo (Planificación › plan › «Cancelar»): los pedidos vuelven a *Listo para
planificar*. Un viaje **despachado** y una factura **aprobada** no se deshacen: para una demo nueva con
los mismos números, reconstruir QAS como dice `docs/environments/QAS.md` o usar otra fecha
(`--service-date`), otros números de pedido y otro número de factura. Un pedido anulado
(`SO-2026-10041`) se reabre desde su ficha si hace falta.

## 7 · Versión corta (7 min)

(a) un cajón de perfil (`PLP-571`) → (b.1) la columna «Ocupa» → (b.3) dos pedidos en una camioneta →
(b.4) la caja baja re-apila las sillas a 2,17 m y rechaza las gavetas por piso → (c.4) el despacho del
almacén en el viaje → (f.3) la factura de TRLIT: 68,73, IVA 0 %, retención 0,69, a pagar 68,04 → (g)
una frase.

## 8 · Discovery (preguntas para PLAPASA)

1. ¿Qué ERP? ¿Con qué número identifica un pedido, y ese número es único por país/sociedad? (define
   el `sourceSystem` real, que reemplaza a `MANUAL`).
2. ¿Pesos unitarios reales y medidas de la caja de **cada** camión de TRLIT y de los demás
   transportistas? ¿Altura de carga habitual (2,40 m)?
3. ¿Cómo cobra hoy el transporte: por viaje, por km, por metro lineal, por bulto? ¿Hay mínimos?
4. ¿Es agente de retención de IVA? ¿Qué porcentaje de IR le retiene hoy a cada transportista (310 al
   1 % u otro, por la resolución de 2026)? ¿Algún transportista RIMPE?
5. ¿Quién emite hoy la guía de remisión electrónica: la planta o el transportista? ¿Desde qué sistema?
6. ¿GPS de la flota tercera? ¿Qué proveedor?

## 9 · Supuestos de esta demo

Pesos unitarios (ERP), perfiles físicos (EWM, «SUPUESTO, medir en planta»), tarifa de TRLIT,
coordenadas aproximadas, RUC, cédulas, clave de acceso y números de comprobante ficticios pero bien
formados. Distancias: estimador geodésico del TMS (sin proveedor de rutas). Porcentajes fiscales: los
de fábrica del régimen de Ecuador (ADR-027 §3), a verificar con el contador.

## 10 · Puntos abiertos que conviene saber

- **Sin cancelación EWM → TMS en esta demo**: los pedidos del EWM de la demo tienen origen `MANUAL` y
  el EWM sólo avisa pedidos con clave de ERP. Con el ERP real y su `sourceSystem`, cruza.
- **RUC de TRLIT en el EWM** (`0990000000301`) no pasa el módulo 11; el TMS usa `0990000034001`. No
  afecta la integración (se compara el código del transportista).
- **Volumen unitario con 4 decimales**: 0,00193 se guarda 0,0019 (1.000 vasos declaran 1,90 m³, no
  1,93). Y un ERP que mande el volumen con más decimales hace que cada reentrega se lea como cambio.
- **Metros lineales con líneas sin medir**: el piso sólo cuenta líneas medidas; una línea sin perfil no
  suma metros lineales a la tarifa.
- **CAM-04 no existe en el EWM** como vehículo: el EWM toma la placa del plan. Si el muelle teclea otra,
  el despacho marca `VEHICLE_MISMATCH` (se registra igual).
