# Guía del sistema eTMS (PDF)

`eTMS_Guia_del_sistema.pdf` — 41 páginas A4, 7,4 MB. Qué hace cada pantalla de eTMS, para qué sirve y
qué hay que mirar, pensada para entregar a un cliente o a un equipo que empieza a usar el sistema.

- **Contenido:** qué es eTMS, sus roles y el circuito pedido → programación → plan → viaje → despacho →
  seguimiento → entrega → provisión → factura → pago; después 55 pantallas en 9 capítulos (primeros
  pasos, pedidos, planificación y viajes, seguimiento, finanzas del flete, maestros, flota y tarifas,
  administración y el portal del transportista). Cubre **los 34 ítems del menú**
  (`frontend/tms-web/src/shared/ui/navConfig.tsx`), «Mi cuenta», el portal móvil y las vistas de
  detalle clave: pedido, tablero del plan, espacio del viaje, factura y perfil de material. Cierra con
  los estados del dominio (de `src/lib/enums.ts`) y un glosario.
- **Capturas:** reales, de TMS QAS (commit `e88989d`, funcionalmente igual a `dev` en `da6852f`), con la
  empresa de demostración **PLAPASA (demo)** (`QAS-PLAPASA`). Los datos son ficticios.
- **Cómo se tomaron:** Playwright contra QAS con los usuarios demo `admin@plapasa.demo` y
  `transportista.trlit@plapasa.demo`, navegando sin un solo clic que escriba (los recorridos
  registraron cero peticiones de escritura). Antes, con autorización para ese tenant, se ejecutó en QAS
  el capítulo de transporte de `docs/demo/PLAPASA_TMS_DEMO.md` por la API (planes del 20 y 21/10,
  VJ-00000013 y VJ-00000014, entrega, factura TRLIT 001-001-000000789 aprobada con cuatro ojos y
  exportada, provisiones), para que las pantallas tengan datos.

Lo que sale vacío, y la guía lo dice en cada ficha: **Seguimiento de despachos**, **Entrega de
documentos** y **Mis viajes** del portal dependen del despacho que manda el eWMS, y el vínculo todavía
no envía (falta la suscripción del webhook del lado del TMS).

Cuando cambien pantallas relevantes, hay que regenerarla: recapturar, revisar el texto de cada ficha y
volver a imprimir. **Toda entrada nueva en `navConfig.tsx` necesita su ficha.**


## Flujo integrado eWMS ⇄ eTMS (PDF)

`EBIM_Flujo_eWMS_eTMS.pdf` — 32 páginas A4, 5,9 MB. Un día de la demo PLAPASA (22/10/2026) recorrido en los
dos sistemas: tres pedidos del ERP con la misma clave en ambos (SO-2026-10090/91/92), el plan PL-00000006 y el
viaje VJ-00000015 del TMS, la carga planificada CRG-000005 que nace en el eWMS, ola, picking, packing y el
despacho SLS-000004 (601) que pone el viaje en ruta solo, la entrega completa y parcial que vuelve al almacén,
la anulación que cruza como alerta y la factura de TRLIT conciliada, aprobada con cuatro ojos y exportada.
Abre con el flujo en una página y la tabla de los siete mensajes entre sistemas; cierra con lo que falta para
producción.

- **Datos:** cargados por la API de cada sistema en los tenants de demostración (eWMS dev y eTMS QAS). Las
  capturas se tomaron después en solo lectura (cero escrituras). TMS QAS todavía no tiene su clave de firma de
  webhooks: los avisos del TMS al eWMS los firmó un script con el secreto del conector, en el formato del TMS, y
  el eWMS leyó el viaje y el pedido reales. El PDF lo dice.
