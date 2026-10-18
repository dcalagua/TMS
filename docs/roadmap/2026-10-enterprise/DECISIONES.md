# Decisiones del pack TMS enterprise (2026-10-18)

> Tomadas para poder trabajar de noche sin preguntar. Todas son **reversibles** y
> están pensadas del lado conservador. Las marcadas «PENDIENTE» las confirma Dennis.

| # | Decisión | Por qué | Estado |
|---|---|---|---|
| D-01 | **El TMS no mueve dinero.** Provisiona, calcula impuestos, exporta y **registra el estado de pago que informa el ERP** (o una persona con cuatro ojos). Sin cuentas bancarias, sin archivos de banco, sin libro mayor | Mantiene la frontera de V46 («TMS validates, ERP pays») y evita guardar datos bancarios. Si se decide que el TMS pague, se agrega encima sin rehacer | PENDIENTE confirmación |
| D-02 | **El EWM es dueño del perfil físico del material**; el TMS guarda una copia por `material_code`. El ERP puede mandar campos físicos en la línea del pedido, que ganan sobre el perfil para esa línea | El almacén mide (CUBISCAN, ficha del material) | Tomada |
| D-03 | **Una señal del otro sistema nunca anula sola.** Anulación de pedido cruza como alerta/retención y decide una persona | Anular un pedido es del ERP/cliente; transporte y almacén anulan **su** parte | Tomada |
| D-04 | Citas de muelle unificadas: el EWM es dueño de sus puertas; el TMS solicita. **Sólo ADR propuesta esta noche** | Dos agendas para una pared es el error que hay que evitar, pero cambia dos productos | PENDIENTE |
| D-05 | Geometría de carga por **huella** (pilas × L × W, alto de pila), no 3D | Suficiente para planificar y explicable; 3D queda detrás del puerto | Tomada |
| D-06 | Defaults Perú configurables: IGV 18 %, detracción 027 al 4 % desde S/ 400, retención 3 %, términos de pago 30 días | Son los valores vigentes conocidos; **se configuran por empresa**, nunca fijos en código | Tomada (verificar % con contabilidad del cliente) |
| D-07 | Provisión nace al **despachar** (configurable a **entregar**) | Es cuando el servicio se considera prestado en la mayoría de clientes | Tomada |
| D-08 | El reparto de costo a pedidos usa una base configurable; sin margen (no hay tarifa de venta) | No inventar ingresos | Tomada |
| D-09 | La rama EWM nace de `feature/plapasa-overnight` (necesita V54); se rebasa cuando PR #44 entre a `dev` | V54 es la base del perfil físico | Tomada |
