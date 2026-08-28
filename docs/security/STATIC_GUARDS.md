# The static guards - what the build refuses

*JOB 15. These are ArchUnit and reflection tests under
`backend/tms-api/src/test/java/com/ebim/tms/architecture/`, run by `./mvnw clean test` like any
other test.*

Each guard here exists because the mistake it catches is **silent**: it compiles, the screen works,
no test fails, and the damage is discovered later by somebody outside the team. That is the bar for
adding one - a rule that merely enforces taste does not belong here, because a guard people argue
with is a guard people disable.

---

## Tenancy

### No service reads a row by bare id

`TenantScopedRepositoryTest#no_service_reads_a_row_by_bare_id`

Spring Data gives every repository `findById`, `existsById`, `deleteById` and `getReferenceById` for
free. They take a primary key and know nothing about tenancy - so a UUID taken out of a request and
passed to one fetches **whatever row it names**, in any company.

RLS (ADR-005) would catch most of these at the database. It is defence in depth and not the
authorization: a query that relies on it has already given up the property this codebase keeps, that
a leak is impossible rather than merely blocked.

**One exception**, `WebhookDeliveryQueue`, documented at the call site: the dispatcher re-reads a row
it has already claimed through the deliberately cross-tenant `claimDue`, on a background thread with
no security context. The id did not come from a request.

### Every own-id finder names the tenant

`TenantScopedRepositoryTest#every_own_id_finder_names_the_tenant`

The same hole written by hand: `findById(UUID)` declared on a repository, with no company predicate.

**What this deliberately does not flag**, because the distinction is the whole design:

* Finders keyed by a **foreign** id (`findByTripIds`, `countByRouteIds`) inherit their scope from
  whoever resolved the parent, which was itself a company-scoped read. This is the pattern the
  codebase uses everywhere - a page of trips resolves its stops in one query - and flagging it would
  produce thirty exemptions, at which point nobody reads the list.
* Finders narrowed by a parent as well as the id (`findByIdAndFrequencyId`) for the same reason.

What backs that exclusion is not this test. It is the composite foreign keys `(id, company_id)` that
make a child of another tenant's parent **unrepresentable** (ADR-003), and
`TenancyConstraintIntegrationTest`, which proves it.

**Found on introduction:** `TenderWaterfallRepository#findByIdForUpdate` - an unscoped locking read
of a waterfall by its own id, with **no callers at all**. Removed. A loaded gun with nobody holding
it is still a loaded gun.

---

## Persistence mapping

### Every persisted enum is stored by name

`PersistenceMappingTest#every_persisted_enum_is_stored_by_name`

`@Enumerated` defaults to `ORDINAL`, which stores the enum's **position**. The day somebody inserts
a value into the middle of an enum - alphabetically, tidily, in a refactor about something else -
every stored row silently changes meaning. A shipment that was `CONFIRMED` becomes `CANCELLED`. No
migration runs. No test fails. Nothing in the application can tell.

The safe mapping is the one you have to remember to type, which is why it needs a guard rather than
a convention. All 46 enum columns here are `STRING`; this is what keeps the forty-seventh from not
being. It also pairs with the database: every enum column has a `CHECK ... IN (...)` naming its
values, and a `CHECK` cannot be written against a position.

### Money is never floating point

`PersistenceMappingTest#money_is_never_a_floating_point_number`

`0.1 + 0.2` is not `0.3` in binary floating point, and a per-kilometre rate over a thousand shipments
accumulates that into an invoice somebody disputes.

Deliberately narrow - it flags fields whose *name* says they are money or a rate. Coordinates,
percentages and durations are legitimately not `BigDecimal`, and a rule that swept them in would be
argued with rather than obeyed.

---

## Secrets

### No view carries a usable secret

`SecretExposureTest#no_view_carries_a_usable_secret`

TMS holds a webhook signing secret and an integration client secret. Each is shown to a person
**exactly once**, through a named show-once view; every ordinary view afterwards carries a
four-character hint, which answers "the one ending 7fQ2" and is useless to anybody who intercepts
it.

The leak this prevents is not a decision anybody makes. It is a field added to a view because it was
on the entity, in a change about something else, reviewed by somebody reading the business logic. It
compiles, the screen works, and the secret is now in every JSON response, every browser cache and
every proxy log - for an unknown length of time.

The two show-once views are listed **by name**, not matched by suffix: "the class that shows a
secret" must be a decision somebody made and can be found, not a naming convention a new class can
join by accident.

---

## The guards that were already here

Named so this document is the whole list rather than the new half of it.

| Guard | Refuses |
|---|---|
| `ModuleBoundaryTest` | A business module reaching into another's tables instead of a port |
| `LayeringTest` | api → application → infrastructure inverted |
| `EndpointContractTest` | An endpoint with no permission, or mixing the two tenancy models |
| `NativeQueryQuotingTest` | Unquoted identifiers in native SQL |
| `AuditVocabularyMigrationTest` | A Java audit action with no matching database `CHECK` |
| `CapabilityTest` | A permission no capability grants |
| `SchemaExposureIntegrationTest` | A table without RLS, a policy, or its grants |
| `TenancyConstraintIntegrationTest` | A composite foreign key that would allow a cross-tenant child |
