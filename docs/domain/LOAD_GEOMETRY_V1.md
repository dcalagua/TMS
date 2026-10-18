# Load geometry v1 (stackable and nestable goods)

ADR-022 sections 3-7, migration **V62**, overnight phase 40. Plan: `docs/roadmap/2026-10-enterprise/02_APILABLES_ANIDABLES.md`.
Decision D-05: **a footprint estimate, not 3D loading and not bin-packing.**

## Why

TMS used to count `quantity x unit volume`. 1,000 PLP-571 cups (10.5 x 10.5 x 17.5 cm, nested 1.2 cm
per cup, 100 per stack) counted as 1.93 m3 when they occupy about 0.15 m3, so planning opened trucks
nobody needed; and a 2.9 m stack was accepted by a 2.5 m box.

## The arithmetic - `shared.geometry.LoadGeometryCalculator`

Pure (no Spring, no clock, no repository). Input: a line's quantity, unit, unit weight and physical
block (V59 snapshot), and a usable height `H`.

```
heightCap = STACKABLE: floor(H / h)    NESTABLE: floor((H - h) / dh) + 1    NONE: 1
weightCap = maxTopLoadKg ? floor(maxTopLoadKg / unitWeightKg) + 1 : unbounded
k         = min(stackMaxQuantity ?: unbounded, heightCap, weightCap)
stacks    = ceil(quantity / k)
height(n) = STACKABLE: n*h    NESTABLE: h + (n-1)*dh    NONE: h
effVolume = (stacks-1) * L*W*height(k) + L*W*height(last)      (last stack exact)
floorArea = stacks * L * W
loadingM  = floorArea / (vehicleWidth * fillFactor)            (only with a width; phase 45 uses it)
```

Rounding HALF_UP: 4 decimals for m3 and m2, 3 for metres.

| Status | When | Figures |
|---|---|---|
| `MEASURED` | everything known | all |
| `UNMEASURED` + reason | `MISSING_LENGTH`, `MISSING_WIDTH`, `MISSING_HEIGHT`, `MISSING_STACK_MODE`, `MISSING_NEST_INCREMENT`, `MISSING_UNIT_WEIGHT` (crush limit without a weight), `UOM_NOT_COUNTABLE` (`KG`, `M3`...), `QUANTITY_NOT_WHOLE`, `UNBOUNDED_STACK` (no `H` and no maximum) | none: **nothing is estimated** (EWM rule 199), the line keeps its declared volume |
| `DOES_NOT_FIT` + `ITEM_TALLER_THAN_VEHICLE` | one unit taller than `H` | described as one unit per stack - what it is in a vehicle tall enough, so the order's stack height names which vehicles can take it |
| *(null)* | the line has no physical block | none; not counted |

`orientationLocked = false` lets a unit wider than the vehicle be turned (`fitsAcross`); turning never
changes the floor area. Reference case, fixed in `LoadGeometryCalculatorTest`: PLP-571, `H` 2.40 m
-> k 100, 10 stacks of 1.363 m, 0.1503 m3, 0.1103 m2.

## On the order (written with the lines)

`TransportOrder.applyLines(lines, declared, H, actor)` computes each line and stores on the line
`geometry_status`, `geometry_reason`, `stacks`, `stack_height_m`, `effective_volume_m3`,
`floor_area_m2`, and on the order:

| Column | Rule |
|---|---|
| `planning_volume_m3` | no measured line: `total_volume_m3`. Lines carry volume: their sum with each measured line's effective volume in place of its naive one. Volume only *declared* (no line carries one): the declaration, unless every line was measured (then the sum of effective volumes) |
| `floor_area_m2` | sum over measured lines |
| `max_stack_height_m` / `max_unit_height_m` | maxima over measured lines (never summed) |
| `geometry_unmeasured_lines` | lines without figures, counted once any line has a block |
| `geometry_reference_height_m` | the `H` used |
| `allocated_floor_area_m2` | V37 ledger, fourth measure |

`H` = `company_settings.reference_load_height_m`, else the property
`tms.planning.geometry.default-reference-height-m` (2.40 in `application.yml`) - `LoadGeometrySettings`.
`total_volume_m3` stays the declared (naive) figure for audit, display and the warehouse plan.
A later profile change rewrites nothing (V59 snapshot rule); an edit recomputes.

`GET /api/v1/orders/{id}` returns `geometry` (`planningVolumeM3`, `floorAreaM2`, `maxStackHeightM`,
`maxUnitHeightM`, `stacks`, `unmeasuredLines`, `referenceHeightM`, allocated/pending floor) and each
line's geometry.

## In capacity and planning

See [`CAPACITY_MODEL.md`](CAPACITY_MODEL.md) v2. Both engines (`HEURISTIC_V1`, `PLANNING_V2`) keep
deciding through `CapacityLimits.accommodates`; they add `ITEM_TALLER_THAN_VEHICLE` (a unit taller
than every vehicle) next to `EXCEEDS_LARGEST_VEHICLE`, and report a `bindingDimension` per proposed
trip. `PLANNING_V2`'s KPIs gain `floorUtilizationPercent` and `heightUtilizationPercent`. Acceptance
test (`LoadGeometryPlanningEnginesTest`): two orders of nestables that need two vans by naive volume
go in one.

Delivery results are recorded in weight, volume and pallets; fulfilment compares them with the
order without the floor (`OrderAmounts.withoutGeometry`). The order's volume in that comparison is
the planning volume: a caller that reports the *naive* volume of a measured order is refused as
exceeding what was ordered (the delivery drawer sends no volume, so it is unaffected).

## Not in v1 (said)

- **Re-stacking per vehicle** (ADR-022 §5): an order is stacked against the reference `H`. A
  vehicle lower than its stacks is refused on height even though shorter stacks would fit; a
  vehicle lower than one unit is refused as `ITEM_TALLER_THAN_VEHICLE`. Conservative (never
  accepts what does not fit); `OrderGeometryPort.restack` is phase 40 cycle 2.
- No 3D, no bin-packing, no mixed-material stacks, no unit conversion.
- Rates by loading metre and warehouse geometry comparison: phase 45 (V63).
