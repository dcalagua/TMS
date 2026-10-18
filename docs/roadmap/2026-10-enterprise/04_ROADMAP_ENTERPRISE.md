# 04 · Hoja de ruta completa del TMS by EBIM

## Fase 0 · Ordenar la casa — **personas, no el pack**

| # | Tarea | Quién |
|---|---|---|
| 0.1 | Push de los 4 commits de ADR-021 en `TMS/dev` | Edu, con OK de Dennis |
| 0.2 | Verificar QAS: versión de Flyway, `/actuator/health`, front de Amplify | Edu |
| 0.3 | Podar ramas ya contenidas en `dev` y los worktrees viejos | Edu |
| 0.4 | Activar CI en GitHub (el pack deja el workflow escrito, sin push) | Edu |
| 0.5 | Crear PRD (proyecto Supabase aparte, Render, Amplify) | Dennis decide |

## Fases 1–3 · Esta noche (pack `TMS-enterprise-overnight-pack`)

| Fase pack | Plan | Repo |
|---|---|---|
| 05 | Mapa de capacidades, CLAUDE.md, docs viejos, workflow de CI | TMS |
| 10 | Diseño: ADR-022…025 + contrato v1.1 | TMS + EWM |
| 20 | Perfil de material en el TMS | TMS |
| 25 | Perfil físico en el EWM (V56) + interfaz `TMS_MATP` | EWM |
| 30 | El EWM usa el plan, POD de vuelta, placa, geometría en el SLS | EWM |
| 35 | Anulaciones de pedido en las dos direcciones, reconciliación, consola | TMS + EWM |
| 40 | Geometría de carga en pedido, capacidad y motores | TMS |
| 45 | Tarifa por metro lineal + geometría del despacho | TMS |
| 50 | Conciliación endurecida (tolerancias, líneas por cargo, CSV) | TMS |
| 55 | Provisión de fletes + mapeo contable + cierre | TMS |
| 60 | IGV, CPE, detracción, retención, NC/ND, penalidades | TMS |
| 65 | Estado de pago, vencimientos, portal «Mis facturas» | TMS |
| 70 | Costo por pedido/cliente + KPIs de finanzas | TMS |
| 80 | Certificación completa + E2E TMS⇄EWM | ambos |
| 90 | Informe de la mañana | ambos |

## Fase 4 · Enterprise (después, cada una con su ADR)

1. **Optimización de rutas** (VRP con ventanas, OR-Tools detrás de `PlanningEngine`). Hoy
   diferida por decisión; desbloquea con un caso de cliente con 30+ paradas/día.
2. **Proveedor GPS real** (adaptador de `TrackingProviderPort`; candidatos locales).
3. **Emisión de GRE transportista electrónica** (OSE/PSE; quién emite: ADR-015 abierta).
4. **Tarifa de venta y margen** por cliente (completa F-5).
5. **Multi-tramo / transbordo / consolidación** (cross-dock de transporte).
6. **Portal del cliente/consignatario** (seguimiento y POD).
7. **Reclamos por daño/faltante** con evidencia y nota de crédito automática.
8. **Scorecard de transportistas** (a tiempo, rechazos de licitación, discrepancias).
9. **Huella de carbono** por viaje (GLEC, factor por tipo de vehículo).
10. **Validación SUNAT en línea** del CPE (`CpeValidationPort`) y conciliación de
    constancias de detracción.
11. **Carga 3D / estantes 2D** detrás de `PlanningEngine` (completa 02).
12. **Citas de muelle unificadas TMS⇄EWM** (ADR propuesta en fase 10, D-04).
