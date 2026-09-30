# TMS ⇄ EWM local end-to-end run

This harness runs the real contract (`docs/integrations/WAREHOUSE_EXECUTION_V1.md`, ADR-013) between
TMS by EBIM and EWM by EBIM. Both backends run as they ship, on disposable local infrastructure, and
every step goes through each product's public HTTP API: the signed webhook, the plan pull, the
milestones, and `DISPATCH_CONFIRMED`. Neither product reads the other's tables. The harness uses SQL
only to seed identity rows and to read evidence from its own two throwaway databases.

```bash
scripts/e2e/tms-ewm/run.sh                    # up + scenarios s1..s9 + down   (about 20 minutes)
KEEP=1 scripts/e2e/tms-ewm/run.sh             # same, but leave everything running afterwards
scripts/e2e/tms-ewm/run.sh up                 # just the environment
scripts/e2e/tms-ewm/run.sh scenarios s1 s5    # some scenarios against a running environment
scripts/e2e/tms-ewm/run.sh down               # stop both JVMs + JWKS, remove the two containers
BUILD=1 scripts/e2e/tms-ewm/run.sh            # rebuild both jars first
```

**Requirements**

- Docker running.
- Java 21.
- `python3` (standard library only), `openssl`, `lsof`, `curl`.
- The EWM worktree at `../IACLAUDE/WMS-by-EBIM-tms-connector`, on branch `feature/tms-connector-v1`. Set
  `EWM_ROOT` if it lives elsewhere.

## What it starts

| Piece | Where | Notes |
|---|---|---|
| TMS database | `tmsewm-e2e-tms-db`, `postgis/postgis:17-3.5`, `127.0.0.1:55440` | Flyway migrates it on TMS start. The app connects as the container superuser and `SET ROLE tms_app`s per request (ADR-005, `TenantRuntimeRoleCheck` goes UP) |
| EWM database | `tmsewm-e2e-ewm-db`, `postgres:17-alpine`, `127.0.0.1:55441` | Flyway runs first. The Supabase-owned tables (`lib/ewm_supabase_owned_tables.sql`, generated from EWM's `SupabaseOwnedTables.java`) and warehouses `CD01`/`CD02` are created **after** it, because Flyway refuses a non-empty `public` that has no history |
| JWKS | `127.0.0.1:55499` | Stands in for Supabase Auth for both products. `lib/jwt_tool.py` generates a per-run RSA key and mints RS256 tokens: `iss=http://127.0.0.1:55499/auth/v1`, `aud=authenticated`, `sub`=the seeded user |
| TMS | `:8080`, profile `local` | Webhooks on with a generated key, with `TMS_WEBHOOK_ALLOW_INSECURE_TARGETS` / `TMS_WEBHOOK_ALLOW_PRIVATE_TARGETS`. The retry ladder is shortened (20s base, 2m cap, 3s poll) |
| EWM | `:8081`, profile `local` | `WMS_AUTH_MODE=jwt` against the same JWKS; the outbound monitor's manual resend needs a real principal, which dev auth does not provide. The private-network allowlist is `127.0.0.1/32,::1/128` (on macOS `localhost` resolves to `::1` first). Outbound and TMS workers poll every 3s |

Everything generated (keys, secrets, logs, pids, `state.env`, `evidence-*.json`) goes to `$E2E_WORK`,
default `/tmp/tms-ewm-e2e`. Nothing secret is written into the repository.

## Master data (the same business keys on both sides)

| | TMS | EWM |
|---|---|---|
| ERP order namespace | `transport_order.external_source = SAPB1_PE` | `out_order.source_system = SAPB1_PE` |
| Warehouse | origin `CD-LIMA` with `external_reference = CD01` | `warehouses.code = CD01` |
| Carrier | `TRSA` | `md_carrier TRSA` |
| Vehicle | `B7K-812` … `B7K-844` (one per concurrent trip) | `md_vehicle B7K-812` |
| Material | order line `SKU-100`, 10 kg, 0.01 m3 | item `SKU-100`, 10 KG, 0.01 M3, stock seeded per order |
| Link | integration client with `shipment:read`, `dispatch:write`, `warehouse-milestone:write` and `order:write`; webhook subscription `SHIPMENT_CONFIRMED`/`CHANGED`/`CANCELLED` pointing at EWM's `webhookPath` | TMS connector with `baseUrl http://localhost:8080`, that credential, the subscription's secret, `sourceSystem EWM_EBIM` |

## Scenarios (`lib/scenarios.py`)

| | What is driven |
|---|---|
| s1 | `EXTERNAL_REQUIRED`: order in both products, plan and confirm, webhook → pull → load `CRG-…` with `external_load_number = SH-…`, milestones, pick/pack/load 97 of 100, ship → `APPLIED`, `IN_TRANSIT`, `INTEGRATION`, `MISMATCH` / `QUANTITY_VARIANCE` 100→97, Control Tower `DISPATCH_MISMATCH` |
| s2 | `HYBRID`: manual dispatch first, then the SLS → `RECONCILED`, one `TRIP_DISPATCHED` |
| s3 | EWM stopped: webhook attempts fail and retry, manual dispatch still works, EWM restarted → redelivery → load → SLS → `RECONCILED` |
| s4 | TMS stopped: EWM ships, the delivery dead-letters, TMS restarted, resent from the EWM monitor → `APPLIED` with the **historical** time. **4b**: an SLS stamped before the trip's confirmation → `UNAPPLIED` + `NOT_APPLIED`, `MISMATCH`, advisory |
| s5 | The same SLS again: same key (inbox replay) and new key → `UNCHANGED`, one business effect. The EWM monitor refuses to resend a `SENT` delivery |
| s6 | `HYBRID`: manual `/dispatch` and EWM's exact SLS fired at the same instant → one dispatch. EWM's own copy recovered as revision 2 → `RECONCILED`. Plus four more TMS-only races |
| s7 | Trip cancelled while the EWM load is `LOADING` → EWM exception `LOAD_IN_PROGRESS_REQUIRES_INTERVENTION`, then the SLS → `UNAPPLIED`/`TRIP_CANCELLED` + advisory. **7b**: cancelled while `DRAFT` → EWM cancels the load, and `WAREHOUSE_LOAD_CANCELLED` reaches the timeline |
| s8 | Wrong warehouse, as master-data drift: after the plan went to CD01, the TMS origin is re-pointed to CD02, and the SLS says CD01 → `WAREHOUSE_MISMATCH`. The origin is restored afterwards |
| s9 | Split: 60 of 100 on trip 1 → trip leaves via SLS, the order stays `READY_FOR_PLANNING` with 40 pending and eligible. **9b**: the 40 on trip 2 → second load for the same ERP order → order `IN_EXECUTION`, EWM order `COMPLETED` |

The run needs about 30 trips' worth of vehicles on one planning date. Rerun from `run.sh`, which
recreates everything, rather than piling runs onto one environment.
