# Material physical profile v1

- Decision: ADR-022 sections 1-2 (and D-02 in `docs/roadmap/2026-10-enterprise/DECISIONES.md`).
- Contract: `docs/integrations/WAREHOUSE_EXECUTION_V1.md` section 7.1.
- Migration: **V59** (`V59__material_profile_and_line_snapshot.sql`). Implemented in overnight-pack phase 20.
- Not here yet: stacks, effective volume, floor area and height as capacity dimensions (ADR-022 sections
  3-7, phase 40, V62). Phase 20 stores the facts those computations need; it computes nothing.

## 1. What it is

A material's **physical profile** is what the warehouse measured about one unit of it: its measures in
loading orientation, how it stacks or nests, how much it weighs and bears. PLAPASA's PLP-571 cup is
0.105 x 0.105 x 0.175 m, nests with a 1.2 cm increment, 100 to a stack.

The warehouse (EWM, `md_item`) **owns** it. TMS keeps a **dated copy** per company and `material_code`
in `tms.material_profile`, written only by the integration API, and never corrects it: when the copy
disagrees with what a planner sees, the place to fix it is the warehouse.

## 2. The copy (`tms.material_profile`)

| Field (API) | Column | Rule |
|---|---|---|
| `materialCode` | `material_code` | Byte for byte the code on `transport_order_line.material_code`; no case folding; unique per company |
| `sourceSystem` | `source_system` | `^[A-Z0-9_]{2,40}$`, required on every write |
| `sourceUpdatedAt` | `source_updated_at` | Required. When the owner changed it |
| `description` | `description` | <= 200 characters |
| `lengthM`, `widthM`, `heightM` | `length_m`, `width_m`, `height_m` | Metres, `> 0`, 4 decimals |
| `unitWeightKg` | `unit_weight_kg` | `> 0`, 3 decimals |
| `unitVolumeM3` | `unit_volume_m3` | `> 0`, 4 decimals |
| `stackMode` | `stack_mode` | `NONE`, `STACKABLE`, `NESTABLE`, or null (not declared) |
| `stackMaxQuantity` | `stack_max_quantity` | Units per stack, `> 0` |
| `nestIncrementM` | `nest_increment_m` | Required when `NESTABLE`, forbidden otherwise |
| `maxTopLoadKg` | `max_top_load_kg` | Crush limit of the bottom unit, `> 0` |
| `orientationLocked` | `orientation_locked` | Defaults to `false` |
| - | `updated_by_client` | The credential that wrote the current content |
| - | `version` | Optimistic lock; bumps only on a real change |

Tenant isolation: RLS `p_tenant_company_scope` (ADR-005); `tms_app` holds `SELECT, INSERT, UPDATE`,
**no DELETE** - a profile the warehouse stops sending stays as the last known copy.

## 3. Writing it (warehouse -> TMS)

`PUT /integration/v1/material-profiles/{materialCode}` and `POST /integration/v1/material-profiles/batch`
(<= 500 items), scope `integration.material:write`, `Idempotency-Key` honoured through the integration
inbox (same key + other body = `409 idempotency-key-reused`). The rules, in the order they apply
(`MaterialProfileIntakeService`):

1. **Stale.** A `sourceUpdatedAt` older than the stored one is `STALE`: `200`, nothing applied, and the
   response carries the stored version and timestamp.
2. **Merge.** An **absent** field keeps the stored value; an explicit **`null`** clears it. On a first
   write absent means empty. (The controller reads the JSON as a tree to tell the two apart.)
3. **Validate the result**, not the payload: a PUT naming only `"stackMode": "NESTABLE"` is a `400` when
   the stored copy has no nest increment.
4. **Detect change.** Numbers are rounded to the column's scale and compared by value. The same content is
   `UNCHANGED` and writes nothing - not even a newer `sourceUpdatedAt` - so a redelivery never bumps the
   version an order line records.

A code containing `/` cannot travel in the PUT path (an encoded slash is refused by the server); send it
in the batch, where the code is in the body.

Outcomes: `CREATED` (201), `UPDATED`, `UNCHANGED`, `STALE` (200). In a batch each item stands alone;
a refused item is `INVALID` with a message and turns the response into `207`. `applied` counts
`CREATED` + `UPDATED`; `refused` counts `INVALID`. Audit: `MATERIAL_PROFILE_CREATED` /
`MATERIAL_PROFILE_UPDATED` on aggregate `MATERIAL_PROFILE`, attributed to the credential.

## 4. Reading it (people)

`GET /api/v1/masterdata/material-profiles?search=&page=&size=&sort=` (`search` matches code or
description; sort by `materialCode`, `sourceUpdatedAt`, `updatedAt`) and `GET .../{id}`, permission
**`masterdata.material_profile:read`** (ORGANIZATION_ADMIN, COMPANY_ADMIN, PLANNER, VIEWER). Screen
**Masters -> Perfiles de material** (`/masters/material-profiles`): list with search, a drawer with every
field, the source system, "Actualizado en origen" (`sourceUpdatedAt`) and "Sincronizado" (`updatedAt`).
Read only, and it says so.

## 5. The snapshot on the order line

`tms.transport_order_line` gains `unit_length_m`, `unit_width_m`, `unit_height_m`, `stack_mode`,
`stack_max_quantity`, `nest_increment_m`, `max_top_load_kg`, `orientation_locked` (the **physical
block**), `physical_source` (`PROFILE` | `ORDER_LINE`) and `material_profile_version`. Every existing
line keeps them null and behaves as before. CHECKs: the profile's rules, plus a source exactly when a
block exists and a version exactly when the source is `PROFILE`.

One rule, one implementation - `orders.application.OrderLinePhysicalResolver` - used by the order form
and the order API (`OrderService`; the integration intake goes through it) and by the bulk import:

1. **The ERP's line block wins as a whole** (`ORDER_LINE`, D-02) when the line names any of `lengthM`,
   `widthM`, `heightM`, `stackMode`, `stackMaxQuantity`, `nestIncrementM`, `maxTopLoadKg`.
   `orientationLocked` alone is not a block. No field is merged in from the profile: an ERP height with a
   warehouse nest increment would describe a stack nobody measured. An invalid block is a `400` naming
   the line.
2. **On an edit**, a line at the same position that still names the same material **keeps its stored
   block**, source and version. Lines are rewritten as a set on every edit, and without this a planner
   changing the service date would re-copy a profile that changed since, or drop an ERP block the form
   never showed.
3. Otherwise **the profile's block is copied** (`PROFILE`, with the profile's version).
4. **Unit weight and volume** stay the line's own. They are filled from the profile only when the line
   omits them, **the order declares no total for that measure**, and the line's unit is a count (not
   `KG`, `M3`, `L`, ... - `shared.geometry.UnitsOfMeasure`). The declared-total condition keeps every
   payload that was valid before V59 valid after it (`OrderTotals.mismatches` would otherwise refuse an
   order whose declared weight disagreed with weights the profile supplied).

A later profile change rewrites nothing: not a redelivery of the order (the intake compares the
*effective* lines, so it answers `UNCHANGED`), not an edit from the form. A new order is when a new copy
is taken. Re-applying a profile to open orders would be a future explicit action.

The order detail (`GET /api/v1/orders/{id}`) returns the block, `physicalSource` and
`materialProfileVersion` per line. The order API's line (`OrderRequest.OrderLineRequest`) and the
integration line (`OrderUpsertRequest.LineRequest`, contract v1.1) accept the eight physical fields as
optional; a caller that sends none behaves exactly as before.

## 6. Not in v1

- No geometry is computed (phase 40). No change to capacity, planning, rates or trips.
- No human edit of a profile, and no delete.
- The bulk import template has no physical columns; imported lines take the profile copy only. Its
  preview does not show the profile-filled unit weights (the import writes them).
- No unit conversion: the warehouse sends metres and kilograms, converted only from a declared unit.
