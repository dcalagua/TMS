# Guía del sistema eTMS (PDF)

`eTMS_Guia_del_sistema.pdf` — **edición 2**, 49 páginas A4, 9,3 MB. Qué hace cada pantalla de eTMS, para
qué sirve y qué hay que mirar, pensada para entregar a un cliente o a un equipo que empieza a usar el sistema.

- **Contenido:** qué es eTMS, sus roles y el circuito pedido → programación → plan → viaje → despacho →
  seguimiento → entrega → provisión → factura → pago; después 68 pantallas en 9 capítulos (primeros
  pasos, pedidos, planificación y viajes, seguimiento, finanzas del flete, maestros, flota y tarifas,
  administración y el portal del transportista). Cubre **los 38 ítems del menú**
  (`frontend/tms-web/src/shared/ui/navConfig.tsx`), «Mi cuenta», el portal móvil, la página pública de
  seguimiento y las vistas de detalle clave. Cierra con los estados del dominio, **la evaluación del
  producto por área (64 → 70 sobre 100) con lo que sigue** y un glosario.
- **Edición 2 (22/10/2026):** suma las pantallas de la integración `tms/mejoras-noche-2026-10-22`,
  marcadas «Nuevo ·» en el menú de cada ficha: scorecard de transportistas, centro de incidencias,
  reclamos de carga, documentos de flota, huella de carbono (período y viaje), puertas y muelles con el
  tablero de citas, hoja de ruta imprimible, enlace y página de seguimiento para el cliente, alcances de
  integración, contraseña en «Mi cuenta», roles en español y la ejecución de paradas y entregas desde el
  portal.
- **Capturas:** las de la edición 1 son de TMS QAS (commit `e88989d`), empresa de demostración
  **PLAPASA (demo)**. Las nuevas salen de la rama integrada corriendo en local contra una **copia** de la
  base de QAS (pg_dump restaurado en un PostGIS desechable, V70–V74 aplicadas por Flyway); en QAS se verán
  igual cuando se despliegue. Las capturas registraron cero escrituras; los datos de esas pantallas
  (un reclamo, un enlace, tres citas, una entrega desde el portal) se crearon por la API **sólo en la
  copia local**. Los datos son ficticios.

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
