# 02 · Productos apilables y anidables en planificación y envíos

> Caso que lo motiva: PLAPASA fabrica gavetas/jabas **apilables** y vasos/baldes
> **anidables**. Hoy el TMS suma `cantidad × volumen unitario` y abre camiones de más
> para lo anidable, y no detecta una pila que no entra de alto.

## 1 · Modelo

### Perfil físico (snapshot en la línea del pedido)

| Campo | Significado |
|---|---|
| `length_m`, `width_m`, `height_m` | Una unidad, en su orientación de carga |
| `unit_weight_kg` | Peso unitario |
| `stack_mode` | `NONE` (no se apila: cada unidad ocupa su huella) · `STACKABLE` (una sobre otra, alto = k·h) · `NESTABLE` (una dentro de otra, alto = h + (k−1)·Δh) |
| `stack_max_quantity` | Máximo de unidades por pila — **mismo significado** que `md_item.stack_max_quantity` del EWM |
| `nest_increment_m` (Δh) | Alto que agrega cada unidad anidada. Obligatorio si `NESTABLE` |
| `max_top_load_kg` | Peso que soporta la unidad de abajo (aplastamiento). Opcional |
| `orientation_locked` | Si se puede girar 90° en el piso |

Origen: `tms.material_profile` (sincronizado desde el EWM, `01_SINCRONIZACION` S-1) o
campos opcionales en la línea del pedido que manda el ERP. **Sin perfil = comportamiento
actual** (volumen declarado, sin piso, sin altura).

### Lo que guarda el vehículo

`vehicle_type.length_m/width_m/height_m` ya existen (V9). Se agrega
`load_floor_fill_factor` (0–1, por defecto 0,85, configurable por empresa y por tipo) y se
**usan** por primera vez.

## 2 · Cálculo (`LoadGeometryCalculator`, puro, sin base ni reloj)

Para una línea con perfil completo y un alto útil `H` (el del vehículo, o el de referencia
de la empresa al crear el pedido):

```
cap_alto   = STACKABLE: floor(H / h)
             NESTABLE : floor((H − h) / Δh) + 1
             NONE     : 1
cap_peso   = max_top_load_kg ? floor(max_top_load_kg / unit_weight_kg) + 1 : ∞
k          = min(stack_max_quantity ?: ∞, cap_alto, cap_peso)        (k ≥ 1)
pilas      = ceil(cantidad / k)
alto_pila  = STACKABLE: k·h · NESTABLE: h + (k−1)·Δh · NONE: h
vol_ef     = pilas × L × W × alto_pila           (m³ «reales» que ocupa)
piso       = pilas × L × W                       (m²)
metros_lin = piso / (ancho_vehículo × fill_factor)
```

- La última pila puede ir incompleta: `alto_ultima` se calcula aparte (vol_ef exacto).
- Si `h > H` → la línea **no entra** en ese vehículo (motivo `ITEM_TALLER_THAN_VEHICLE`).
- Lo que no se puede calcular (falta una medida) **no se estima**: la línea usa el volumen
  declarado y se cuenta en `geometryUnmeasuredLines` (regla 199 del EWM).
- Ejemplo de referencia (prueba unitaria fija): PLP-571, 10,5×10,5×17,5 cm, Δh 1,2 cm
  (supuesto de prueba), máx 100/pila, H = 2,4 m → k = 100, alto = 1,363 m,
  1.000 u = 10 pilas, vol_ef ≈ 0,150 m³ (vs 1,93 m³ ingenuo).

## 3 · Dónde se usa

1. **Pedido** — al crear/editar líneas se guardan en la línea y en los totales:
   `effective_volume_m3`, `floor_area_m2` (sumables) y `max_stack_height_m` (máximo, no se
   suma). `total_volume_m3` (ingenuo) se conserva para auditoría.
2. **Capacidad del viaje** — `CapacityLimits.accommodates` gana dos dimensiones:
   piso (`Σ floor_area ≤ L×W×fill`) y alto (`max_stack_height ≤ H`); el volumen usa el
   efectivo cuando la línea lo tiene. Un nulo sigue significando «sin límite» y 0 sigue
   siendo un límite real (`CAPACITY_MODEL.md`).
3. **Re-apilado por vehículo** — si el vehículo es más bajo que el `H` de referencia, la
   línea se recalcula con el `H` del vehículo antes de decidir (no se prorratea).
4. **División parcial** — una porción de la línea **recalcula** su geometría (el volumen
   efectivo no es lineal por el redondeo de pilas).
5. **Motores** `HEURISTIC_V1` y `PLANNING_V2` — usan el mismo `accommodates`; el KPI de
   utilización informa la **dimensión que ata** (peso, volumen, piso, alto, pallets).
6. **Tarifas** — `CostUnit.LOADING_METER` nuevo; `VOLUME` lee el efectivo si existe.
7. **Despacho del almacén** — el SLS trae `stacks`, `effectiveVolumeM3`, `loadingMeters`;
   el TMS los compara con lo planificado y muestra la diferencia (no bloquea).
8. **UI** — formulario del pedido muestra «Ocupa: 10 pilas · 0,15 m³ efectivos · 0,11 m²
   de piso · alto 1,36 m»; tablero de planificación muestra la barra de piso y la de alto;
   ficha del tipo de vehículo edita L/A/H y factor de piso.

## 4 · Lo que NO se hace (dicho)

- No es carga 3D ni bin-packing: es una **estimación por huella**. Un paso 2D de
  estantes puede agregarse después detrás del puerto `PlanningEngine`.
- No se mezclan materiales en una pila (igual que el EWM: `allow_mixed_materials=false`).
- No se convierte ninguna unidad sin declarar.

## 5 · Del lado EWM

- V56: `stack_mode`, `nest_increment`, `max_top_load` en `md_item` (fase 25).
- `StackCapacityCalculator` gana el anidado para calcular `stacks/effectiveVolumeM3/
  loadingMeters` del SLS (fase 30). La ocupación (`docs/OCCUPANCY.md`) puede usar después
  el volumen efectivo — **no** esta noche (cambiaría un número que el cliente ya vio, D-11).
