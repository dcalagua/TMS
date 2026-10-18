# Demo PLAPASA · capítulo Transporte (TMS by EBIM)

> Para Edu y Dennis. El capítulo de transporte de la demo de PLAPASA (plásticos, Guayaquil, USD,
> America/Guayaquil). Va **inmediatamente después** del capítulo «Carga y despacho» del EWM
> (`WMS-by-EBIM/demo/plapasa/GUION_FLUJO_COMPLETO_PLAPASA.txt`, pasos 8–9, y el capítulo 7 de
> `docs/plapasa/DEMO_LUNES.md` del EWM). Allí se vio salir un camión del almacén; acá se cuenta lo
> mismo desde el lado del transporte: **qué camión hacía falta, cuánto ocupa de verdad, qué cuesta y
> qué pasó en la entrega.**
>
> Datos: `supabase/seeds/plapasa_demo_seed.sql` + los pedidos del ERP de
> `scripts/demo/plapasa/orders.json`. Cómo cargarlo en QAS: [`PLAPASA_TMS_RUNBOOK.md`](PLAPASA_TMS_RUNBOOK.md).
> **Todos los números de abajo salen de `PlapasaDemoSeedIntegrationTest`** (Testcontainers, Flyway
> V1–V68, seed aplicado dos veces, pedidos por la API real, planificación y despacho por los servicios
> reales). Si en pantalla aparece otro número, algo del entorno es distinto: ver §4.

---

## 0 · Qué está vivo y qué no (leer antes de prometer)

| Etiqueta | Qué significa | Capítulos |
|---|---|---|
| `VIVO TMS` | Sólo necesita el TMS desplegado en V68 y el seed cargado | b, parte de c, e (lado TMS), f (provisión, costo para servir) |
| `REQUIERE EWM v1.1` | El EWM desplegado con V56–V58 y el conector TMS configurado (runbook §6–§7). Sin eso: perfiles por el respaldo SQL y el despacho por la persona (modo HYBRID) | a, c (carga «Planificado por TMS», SLS), d (POD en el EWM), e (alerta en el EWM) |
| `DEPENDE DE LA RAMA DE IMPUESTOS` | Factura del transportista con régimen Ecuador. Hoy el formulario es peruano (RUC de 11 dígitos, IGV) | f (factura, retención) |
| `REQUIERE RAMA GEOMETRÍA-UI` | Barras de piso y alto, columna «Ocupa» en el pedido. El cálculo ya existe; la pantalla no | b (se cuenta con la tarjeta del pedido y la barra del viaje) |
| `ROADMAP` | No existe. Se nombra como siguiente paso, nunca se muestra | g |

**Qué NO decir:** que el TMS está «conectado» o «integrado» con el ERP de PLAPASA (no lo está: los
pedidos los manda un script con el formato del ERP); que el almacén ya apila con el perfil (lo declara
y lo manda); que el TMS re-apila solo por vehículo (§b.4: es la próxima entrega); que la factura ya
calcula IVA/retención de Ecuador (rama de impuestos).

---

## 1 · El elenco (todo «(demo)», nada real)

| Qué | Valor | Igual que en el EWM |
|---|---|---|
| Empresa TMS | `QAS-PLAPASA` · **PLAPASA (demo)** · país EC · USD · America/Guayaquil · modo de despacho **HYBRID** | — |
| Origen | `GYE-CD` · Planta de inyección y soplado · Km 14,5 Vía a Daule (código de almacén `GYE-CD`) | sí |
| Clientes | `CLI-SUP-01` Supermercados Costa Azul (Kennedy Norte) · `CLI-FER-01` Ferretería El Constructor (centro) · `CLI-AGR-01` Agroexportadora Río Babahoyo (Babahoyo) · `CLI-UIO-01` Distribuidora Andina del Norte (Quito) | sí (códigos, nombres, direcciones) |
| Rutas | `RUTA-GYE-NORTE` (supermercado) · `RUTA-GYE-SUR` (ferretería) · `RUTA-LOS-RIOS` (agro) · `RUTA-SIERRA-NTE` (Quito) · lunes a sábado sin corte | el EWM usa `RUTA-GYE-NORTE` |
| Transportista | `TRLIT` · Transportes del Litoral S.A. (demo) · RUC **0990000034001** (válido, módulo 11) | código sí, RUC no (el del EWM no pasa el control: §10) |
| Vehículos | `CAM-01` **GBA-1234** camión 10 t (6,2 × 2,4 × 2,4) · `CAM-02` **GBC-5678** furgón 5 t (6,0 × 2,4 × 2,4) · `CAM-03` GSC-2468 camioneta NHR (4,3 × 2,0 × 2,0) · `CAM-04` GSD-1357 furgón **caja baja 2,20 m** | CAM-01 y CAM-02 sí |
| Tarifa | `TRLIT-2026-USD`: USD 45 por viaje + 0,85/km + 5/parada + **6,50 por metro lineal** · mínimo 60 | — |
| Personas | `admin@plapasa.demo` (administración) · `planner@plapasa.demo` (planificación) · `transportista.trlit@plapasa.demo` (portal) | mismo correo de admin que el EWM, otra cuenta |

Los pedidos del ERP (`orders.json`), con la **misma clave que el EWM**: `externalSource = MANUAL` +
el N° del cliente del EWM. Es `MANUAL` porque así los tiene el EWM de la demo (los creó su cargador
sin sistema de origen); el plan y el despacho se encuentran **byte a byte** por esa clave.

| Pedido ERP | Cliente | Fecha | Líneas | Declarado | **Ocupa** | Piso | Pila más alta |
|---|---|---|---|---|---|---|---|
| `SO-2026-10040` | Ferretería | 20/10 | 30 Utility Box · 24 tachos 72 l · **40 sillas Costabela** · 60 baldes 10 l | 20,76 m³ | **4,58 m³** | 2,33 m² | **2,35 m** (25 sillas encajadas) |
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

## 2 · Guion (≈ 15 min; versión corta de 7 min en §8)

Entrar como `planner@plapasa.demo` (y `admin@plapasa.demo` en otra ventana para finanzas e
integraciones). Pestaña aparte con el EWM (`https://ewm.ebim.pe/t/plapasa`) abierta en
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
   - Mostrar también `PLP-700` Silla Costabela: **NESTABLE, 6 cm**. «Una silla monobloque se encaja en
     la de abajo: si la declaráramos "apilable", la pila de diez sillas mediría 9 m.»
   - Mostrar `PLP-1601` Gaveta Agro Alta: **STACKABLE**, 10 por pila, **150 kg** encima.
3. Si el origen dice **`PLAPASA_DEMO_SEED`** en vez de `EWM_EBIM`: se cargó el respaldo (§4). Decir la
   verdad: «estos los cargamos nosotros con los mismos valores del almacén; en producción llegan
   solos».

### (b) Los pedidos del ERP y el planificador — 5 min · `VIVO TMS`

1. **Operación › Pedidos.** Seis pedidos con el número del ERP (`SO-2026-…`) al lado del nuestro
   (`OT-0000000n`). Abrir `SO-2026-10045` (5.000 vasos): la ficha muestra el volumen **declarado
   9,50 m³** (la columna «Ocupa» es de la rama geometría-UI; el dato ya está en la API:
   `GET /api/v1/orders/{id}` → `geometry`, y en el plan, ver el punto 2).
   - «Ayer el TMS hacía esta cuenta: cantidad por volumen unitario. 1.000 vasos = 1,90 m³.
     Pero un vaso entra en el otro: **1.000 vasos son 10 pilas de 1,363 m, 0,15 m³ y 0,11 m² de
     piso.**» (Catálogo: 1,93 m³; el TMS guarda el volumen unitario con 4 decimales y muestra 1,90.)
2. **Operación › Planificación** · origen `GYE-CD` · fecha **21/10/2026** · «Nuevo plan».
   En la lista de pedidos elegibles, la tarjeta de `SO-2026-10045` dice **«140 kg · 0,75 m³»** y la
   de `SO-2026-10046` **«202 kg · 0,98 m³»**: es lo que **ocupan**, no lo declarado.
3. **Dos pedidos, una camioneta.** Crear un viaje con **CAM-03 (camioneta NHR, 17,2 m³)**. Asignar
   `SO-2026-10045` y después `SO-2026-10046`. Los dos entran.
   - Barra de volumen del viaje: **1,73 m³ de 17,2** (10 %). Piso 1,36 m² de 7,31; alto 1,363 m de
     2,00.
   - «Con la cuenta de ayer, estos dos pedidos sumaban **20,23 m³**: no entraban en una camioneta de
     17,2. Hacían falta dos. Hoy entran en una, y sobra lugar para la ruta del día.»
   - Este plan **se deja en borrador** (estos dos pedidos no existen en el EWM de la demo; confirmarlo
     le mandaría al almacén un plan con pedidos que no conoce). Botón «Cancelar plan» o dejarlo.
4. **La pila demasiado alta para la caja baja.** Otro plan: origen `GYE-CD` · **20/10/2026**.
   Crear un viaje con **CAM-04 (furgón caja baja, 2,20 m)** y asignar `SO-2026-10040` (ferretería).
   **Rechazo**, con la causa:
   > *Order OT-00000001 does not fit trip 1: stack height 2.35 m exceeds the capacity of 2.2 m.*
   - «Veinticinco sillas encajadas miden 2,35 m. Volumen, piso y peso sobran: lo único que falla es el
     alto, y el sistema dice eso, no "no entra". Ningún planificador carga ese camión y se entera en la
     puerta.»
   - **Re-apilado (decirlo como siguiente paso, no mostrarlo):** «la próxima entrega re-apila por
     vehículo: bajaría la pila a 22 sillas, 2,17 m, y entraría. Hoy el TMS es conservador: nunca
     acepta lo que no entra.» (`ROADMAP` — ADR-022 §5, ciclo 2.)
   - En el mismo viaje, cambiar el vehículo a **CAM-02 (GBC-5678, furgón 5 t, 2,40 m)** y volver a
     asignar `SO-2026-10040`: **entra**. Ocupa 4,58 m³ de 34,56; piso 2,33 m² de 12,24.
     «Declarado: 20,76 m³. Ocupa: 4,58. Las 40 sillas solas bajan de 13,43 m³ a 1,51.»
5. **El camión justo.** Nuevo viaje con **CAM-01 (GBA-1234, camión 10 t)** · asignar
   `SO-2026-10034` (agro). Entra: **piso 11,85 m² de 12,65 (94 %)**, alto 2,32 de 2,40, volumen
   25,81 de 35,71 (72 %), 864 kg de 10 t. «Este camión no se llena por peso ni por volumen: se llena
   **por piso**. 55 pilas de gavetas. Un TMS que sólo mira kilos y m³ diría que sobra un 30 %.»
   - (Si preguntan: en la caja baja también rebota por alto, 2,32 m > 2,20.)
6. A cada viaje: **Ruta** (`RUTA-GYE-SUR` y `RUTA-LOS-RIOS`, «aplicar secuencia») y **Conductor**
   (`COND-02` y `COND-01`). **Confirmar el plan.** Nacen `VJ-…` (dos viajes).

### (c) Viaje, almacén, despacho y torre de control — 4 min · `REQUIERE EWM v1.1`

1. **EWM** · Integraciones › TMS › «Avisos recibidos»: el aviso `SHIPMENT_CONFIRMED` del viaje
   (firmado, recibido). «El TMS no le escribe al almacén: le avisa, y el almacén lee el plan.»
2. **EWM** · Salida › Cargas: aparece la carga `CRG-…` con número externo **`VJ-…`**, transportista
   TRLIT, tracto **GBC-5678**, ruta `RUTA-GYE-SUR`, y la pestaña **«Planificado por TMS»**: 285,8 kg,
   **4,58 m³** (lo que el TMS reservó en el camión: lo que ocupa, no los 20,76 declarados) y la ventana
   del cliente **08:00–12:00**.
   «Informa, no valida: el almacén carga lo que carga y lo compara.»
3. **EWM** · el operario sube la caja (`OBLPN-…` de `SO-2026-10040`, empacada antes: §5) a la carga
   → «Marcar lista para despachar» → **Despachos** · precinto → `SLS-…` + **601**.
4. **TMS** · Operación › **Viajes** › el `VJ-…` de la ferretería → tarjeta **«Despacho de almacén»**:
   - el SLS recibido, **revisión 1**, «Plan contra despachado»: **verificación OK** (los 4 renglones:
     30 / 24 / 40 / 60 UN contra lo planificado);
   - el viaje pasó solo a **En tránsito** y el pedido a **En ejecución**, atribuido a la credencial del
     almacén (modo HYBRID: «el primero que llega despacha»);
   - «Hitos del almacén»: carga iniciada / lista.
   - Si el almacén apiló distinto, aparece el chip **«Huella distinta a la planificada (informativa)»**:
     «el almacén apila sin la altura del camión; por eso es informativo, nunca bloquea».
5. **Torre de control**: el viaje en tránsito; ninguna alerta del despacho. (Si el SLS hubiera traído
   otra placa o un pedido de más, aparecería acá con su código.)

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

### (f) Finanzas del flete en Ecuador — 2 min · costo y provisión `VIVO TMS` · factura `DEPENDE DE LA RAMA DE IMPUESTOS`

1. **Viaje de la ferretería** → Costo → «Estimar»: **USD 68,73** = 45,00 por viaje + **16,30**
   (19,2 km × 0,85) + **7,43** (1,14 m lineales × 6,50). «Se cobra lo que el camión gasta de **largo**:
   2,33 m² de piso sobre 2,04 m de ancho útil.» Viaje del agro: **USD 147,44** = 45,00 + 64,69
   (76,1 km) + 37,75 (5,81 m lineales).
2. **Provisiones**: el viaje despachado **nace provisionado** por su costo estimado (disparador «al
   despachar»). «A fin de mes, contabilidad tiene el flete devengado aunque TRLIT todavía no facture.»
   Mostrar «antigüedad» y «Provisionar ahora». (Requiere el barrido encendido o el botón: runbook §8.)
3. **Factura de TRLIT** (Auditoría de flete) — **depende de la rama de impuestos**: comprobante SRI
   **01 (factura)**, en **USD**, **IVA 0 %** sobre el flete de carga, y la **retención en la fuente del
   IR 1 % (código 310)** que PLAPASA practica y declara. La factura se compara con el costo del viaje
   por el subtotal, y al aprobarla **consume la provisión** en la misma transacción. *Hoy* el formulario
   es el peruano (RUC de 11 dígitos, IGV 18 %): **no registrar una factura de PLAPASA antes de que
   llegue esa rama** (§4).
4. **Costo para servir**: el costo de cada viaje se reparte entre sus pedidos **por volumen efectivo**
   (configuración de la empresa) y se reporta por cliente, destino y ruta. Con los dos viajes del 20/10:
   ferretería ≈ **USD 15,0 por m³ ocupado** (0,24 USD/kg); agroexportadora ≈ **USD 5,7 por m³**
   (0,17 USD/kg). «La ferretería del centro cuesta casi tres veces más por m³ que el agro de Babahoyo,
   aunque esté más cerca: va un camión medio vacío.» (Cifras sobre el costo **estimado**; con el costo
   real cerrado, cambian. No hay margen: no existe tarifa de venta, y la pantalla lo dice.)

### (g) Lo que sigue — 1 min · `ROADMAP`

- **Guía de remisión electrónica SRI** (comprobante 06) emitida desde el viaje, con la placa, el
  conductor y los pedidos que el TMS ya conoce.
- **Re-apilado por vehículo** (la caja baja tomaría la ferretería con pilas de 22 sillas) y la vista de
  piso y alto en el planificador.
- **GPS**: el contrato de posiciones existe (ADR-007); falta el adaptador del proveedor que use TRLIT.
- **Optimización de rutas** (secuencia y asignación automática con ventanas): diferida por decisión;
  hoy la heurística agrupa por corredor y llena el vehículo más grande libre.
- **Citas de muelle unificadas** con el almacén (D-04): propuesta, sin implementar.

**Cierre:** «El inventario cuadra por construcción en el almacén; el camión cuadra por construcción
en el transporte. Y lo que se dicen el uno al otro son referencias de negocio: el número del pedido,
la placa, el código del almacén. Nada más.»

---

## 3 · Números para tener a mano

| Dato | Valor |
|---|---|
| 1.000 vasos PLP-571 | 10 pilas · 1,363 m · 0,1503 m³ · 0,11 m² · declarado 1,90 m³ (catálogo 1,93) |
| SO-2026-10045 + 10046 en CAM-03 | 1,73 m³ de 17,2 · piso 1,36 de 7,31 m² · declarado 20,23 m³ (no entraba) |
| SO-2026-10040 en CAM-04 (caja baja) | rechazo: **stack height 2.35 m exceeds 2.2 m** (sólo alto) |
| SO-2026-10040 en CAM-02 | 4,58 m³ de 34,56 · 2,33 m² de 12,24 · 285,8 kg · USD 68,73 |
| SO-2026-10034 en CAM-01 | **11,85 m² de 12,65** · 2,32 m de 2,40 · 25,81 m³ de 35,71 · 864 kg · USD 147,44 |
| Re-apilado (roadmap) | 22 sillas por pila = 2,17 m → la caja baja la tomaría |
| RUC TRLIT | 0990000034001 (09-9-000003 · verificador 4 · 001) |

---

## 4 · Si algo falla en vivo

| Síntoma | Qué hacer | Qué decir |
|---|---|---|
| Perfiles de material vacíos o con otro origen | Correr el respaldo SQL **antes** de mandar pedidos (runbook §5.b). Si los pedidos ya entraron sin perfil, no se re-copian: re-enviar con `--service-date` no alcanza; anular y volver a crear sólo en un ensayo | «Este dato lo manda el almacén; hoy lo cargamos nosotros con sus mismos valores» |
| La tarjeta del pedido muestra 9,50 m³ en vez de 0,75 | Los pedidos entraron antes que los perfiles (snapshot). Ídem fila anterior | — |
| `SO-2026-…` no aparece en el plan del 20/10 | La fecha pasó: la integración no libera pedidos vencidos (CUTOFF_MISSED). `plapasa_tms_demo.py orders --service-date <hoy o mañana>` antes de planificarlos | «La programación respeta los cortes: un pedido tarde no se libera solo» |
| El EWM no crea la carga | EWM › Integraciones › TMS › Avisos: si no llegó, «Sincronizar» con el número `VJ-…`; si llegó con error de firma, revisar el secreto (runbook §6). Plan B: despachar el viaje a mano en el TMS (modo HYBRID) | «En modo híbrido, el primero que despacha manda y el otro concilia» |
| El SLS llega pero el viaje no se mueve (`UNAPPLIED`) | Torre de control → el aviso dice la causa (p. ej. el transportista no es dueño del vehículo) | Leer el motivo en voz alta: el sistema dice por qué no |
| `VEHICLE_MISMATCH` | La placa del EWM no es GBA-1234/GBC-5678 (se tecleó un remolque como tracto) | «El despacho se registra igual: un hecho no se rechaza, se marca» |
| No deja registrar la entrega | Falta «Llegó» en la parada, o la hora es futura | — |
| Factura rechazada por RUC | Es el formulario peruano: no mostrar facturas hasta la rama de impuestos | «Ecuador entra con su régimen: IVA, retención 310, SRI» |
| Error en inglés en el rechazo de capacidad | Es el texto del servidor; traducirlo en voz alta | — |

---

## 5 · Preparación (la víspera y 30 min antes)

**La víspera**

- [ ] Runbook completo en QAS (§1–§9), incluido el smoke.
- [ ] `python3 scripts/demo/plapasa_tms_demo.py check` sin faltantes (perfiles `EWM_EBIM` o el respaldo).
- [ ] Pedidos enviados (`… orders`): seis `CREATED`; una segunda corrida da seis `UNCHANGED`.
- [ ] En el EWM, `SO-2026-10040` reservado, asignado, con ola, pickeado y **empacado** (OBLPN cerrado
      «listo para carga»), para que en vivo sólo se suba a la carga que crea el TMS.
- [ ] Ensayar (b)–(e) una vez y **reconstruir** (§6) si se confirmó algún plan.

**30 min antes**

- [ ] Backend y frontend del TMS despiertos (Render tarda ~1 min si estuvo inactivo).
- [ ] `planner@plapasa.demo` y `admin@plapasa.demo` dentro, empresa **PLAPASA (demo)** seleccionada.
- [ ] Planificación sin planes del 20/10 ni del 21/10 (si hay de un ensayo: cancelarlos).
- [ ] Pedidos: `SO-2026-10040/34/41/45/46` **Listos para planificar**, `SO-2026-10021` **No listo**.
- [ ] EWM en Integraciones › TMS: el conector activo y la última respuesta del TMS con hora de hoy.
- [ ] La fecha de servicio sigue siendo futura (si la demo se movió: `--service-date`).

## 6 · Volver a empezar (entre ensayos)

Cancelar los planes de ensayo (Planificación › plan › «Cancelar»): los pedidos vuelven a *Listo para
planificar*. Un viaje **despachado** no se deshace (nunca): para una demo nueva con los mismos números,
reconstruir QAS como dice `docs/environments/QAS.md` o usar otra fecha (`--service-date`) y otros
números de pedido. Un pedido anulado (`SO-2026-10041`) se reabre desde su ficha si hace falta.

## 7 · Versión corta (7 min)

(a) un cajón de perfil (`PLP-571`) → (b.3) dos pedidos en una camioneta → (b.4) rechazo por alto en
la caja baja y cambio a CAM-02 → (c.4) el despacho del almacén en el viaje → (f.1) el costo en USD con
metros lineales → (g) una frase.

## 8 · Discovery (preguntas para PLAPASA)

1. ¿Qué ERP? ¿Con qué número identifica un pedido, y ese número es único por país/sociedad? (define
   el `sourceSystem` real, que reemplaza a `MANUAL`).
2. ¿Pesos unitarios reales y medidas de la caja de **cada** camión de TRLIT y de los demás
   transportistas? ¿Altura de carga habitual (2,40 m)?
3. ¿Cómo cobra hoy el transporte: por viaje, por km, por metro lineal, por bulto? ¿Hay mínimos?
4. ¿Agente de retención? ¿Qué códigos de retención usa con cada transportista (310 u otros)?
5. ¿Emite hoy la guía de remisión electrónica? ¿Quién: la planta o el transportista?
6. ¿GPS de la flota tercera? ¿Qué proveedor?

## 9 · Supuestos de esta demo

Pesos unitarios (ERP), perfiles físicos (EWM, «SUPUESTO, medir en planta»), tarifa de TRLIT,
coordenadas aproximadas, RUC y cédulas ficticios pero válidos. Distancias: estimador geodésico del TMS
(sin proveedor de rutas).

## 10 · Puntos abiertos que conviene saber

- **Sin cancelación EWM → TMS en esta demo**: los pedidos del EWM de la demo tienen origen `MANUAL` y
  el EWM sólo avisa pedidos con clave de ERP. Con el ERP real y su `sourceSystem`, cruza.
- **RUC de TRLIT en el EWM** (`0990000000301`) no pasa el módulo 11; el TMS usa `0990000034001`. No
  afecta la integración (se compara el código del transportista).
- **Volumen unitario con 4 decimales**: 0,00193 se guarda 0,0019 (1.000 vasos declaran 1,90 m³, no
  1,93). Y un ERP que mande el volumen con más decimales hace que cada reentrega se lea como cambio.
- **Metros lineales con líneas sin medir**: el piso sólo cuenta líneas medidas; una línea sin perfil no
  suma metros lineales a la tarifa.
