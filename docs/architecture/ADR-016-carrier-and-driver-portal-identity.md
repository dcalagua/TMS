# ADR-016 - Carrier and driver portal: identity before screens

**Status:** Proposed - **blocked on a security decision; nothing implemented.**
**Date:** 2026-09-27
**Constrained by:** ADR-001 (React -> Spring Boot -> PostgreSQL), ADR-003 (company scope), ADR-005
(tenant RLS), ADR-006 (evidence storage), ADR-007 (positions inform, never move a lifecycle),
ADR-009 (order execution lifecycle)

## Context

The brief for a first, online-first carrier portal asks for: my trips, stops, orders, documents,
arrival, start of service, delivery, rejection, incident, proof of delivery (photo, signature,
remark), every mutation with an idempotency key. It also says: *if the current architecture does not
allow a secure carrier/driver identity without improvising, do not invent shared users - write an
ADR and leave the phase documented.*

An audit of the identity model on 2026-09-27 found:

| Question | What exists |
|---|---|
| Can a driver sign in? | **No.** `tms.driver` (V26) is a fleet master: name, document, licence, optional carrier. It has no link to `tms.app_user` or to a Supabase Auth account |
| Can a carrier sign in? | **No.** `tms.carrier` is a master. The only carrier-bound principal is an **integration credential** (`integration_client.carrier_id`, V31), a machine credential for tender answers |
| What scopes a person? | `membership (app_user, organization, company)` + roles. Access is **company-wide**: a member sees every trip of the company. There is no narrower scope (per carrier, per vehicle, per driver) |
| Where are execution writes checked? | `TripStopExecutionService`, `TripDeliveryService`, `TripExceptionService` - all require `planning.trip:execute` and check only the company |
| Evidence | `delivery_evidence` + ADR-006's `EvidenceStoragePort` (private store, no public URL) - reusable as is |
| Idempotency for mutations | The integration inbox has it (`Idempotency-Key`); the user-facing API does not |

Giving a driver today's `planning.trip:execute` inside a company membership would let them read and
record deliveries on **every** trip of the shipper, including other carriers' - a cross-carrier
leak. A shared "driver" login per carrier would make every POD unattributable, which ADR-009 and
the audit trail exist to prevent. Both are the improvisations the brief forbids.

## Decision (proposed)

1. **A person with a narrower scope, not a new kind of login.** A driver or a carrier dispatcher is
   an `app_user` (Supabase Auth, as every person), with a new membership **scope**:
   - `membership.carrier_id` (nullable): the person acts for one carrier of the company;
   - `driver.app_user_id` (nullable, unique): the driver record this person *is*.
2. **Row visibility by assignment, enforced in Java and RLS.** A carrier-scoped member sees only
   trips whose `carrier_id` (or `accepted_carrier_id`) is theirs; a driver-bound member only trips
   whose `driver_id` is theirs. Services resolve the scope server-side from the membership - never
   from a header - and every repository query of the portal names it. RLS gains a second policy
   keyed on the same predicate as defence in depth (ADR-005's pattern).
3. **New roles, least privilege.** `CARRIER_DISPATCHER` (read own trips, assign own driver/vehicle)
   and `DRIVER` (read own trips; record arrival, service start, stop completion, delivery,
   rejection, incident, evidence). Neither gets planning, orders, rates or settlement permissions.
4. **Portal endpoints are the existing execution services**, called through a thin
   `/api/v1/portal/**` controller that adds the assignment check. No second delivery engine.
5. **Idempotency on every portal mutation** with an `Idempotency-Key` stored per (app_user, key),
   because a phone on a bad network retries.
6. **Online-first, installable PWA later.** No offline queue in V1 (deferred by decision).

## Why it is not implemented now

The decision changes the security model: a new membership scope, new roles, a second RLS predicate,
and the rule that a company member may see only part of the company. That needs explicit approval
from the product owner and a security review, which this autonomous run cannot give itself. Nothing
in TMS was changed for it.

## Open questions

1. Is a driver always bound to one carrier, or may an own-fleet driver (no carrier) use the portal?
2. Who invites a driver: the shipper's administrator, or the carrier's dispatcher?
3. May a carrier dispatcher see the shipper's order references and customer names, or only stops?
4. Are PODs from the portal final, or reviewed by the shipper before the order closes out?

## Consequences when approved

- Migrations: `membership.carrier_id`, `driver.app_user_id`, two roles, the portal permissions,
  and the RLS predicates.
- The trip workspace and delivery tracking screens are unaffected; the portal is its own route set.
