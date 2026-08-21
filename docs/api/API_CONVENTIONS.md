# TMS by EBIM - API conventions

The contract every TMS endpoint follows. It is owned by the backend and published as OpenAPI at
`/v3/api-docs` (Swagger UI at `/swagger-ui.html`); this document explains the decisions behind
it, which the generated description cannot.

## 1. Shape

- **Base path:** `/api/v1`, configured as `tms.api.base-path`. Controllers use
  `@RequestMapping("${tms.api.base-path}/...")` rather than hard-coding it, so the prefix is
  changed in one place.
- **Media types:** `application/json` for success, `application/problem+json` for errors.
- **Resources are plural nouns** (`/companies`, `/orders`); actions are HTTP methods. A verb in
  a path is reserved for genuine operations that are not CRUD (`/trips/{id}/close`).
- **Ids are UUIDs.**
- **Timestamps are ISO-8601 with an offset** (`2026-08-19T14:30:00Z`). Operational dates that
  belong to a company's working day are plain dates interpreted in the company's `timeZone`,
  which is why `timeZone` is part of every company payload.
- **Money and quantities are decimal**, never floating point, and always carry their unit in
  the field name or in a sibling field.

## 2. Authentication

Every endpoint requires `Authorization: Bearer <Supabase access token>` except:

| Endpoint | Why it is public |
|---|---|
| `GET /api/v1/system/info` | lets the frontend tell "backend down" from "token rejected" before sign-in; returns no tenant or user data |
| `GET /actuator/health`, `/actuator/health/**` | container probes and load balancers |
| `/v3/api-docs`, `/swagger-ui**` | development convenience; **authenticated in production** (`tms.api.public-documentation=false`) |

Details of token validation are in
[`../security/SECURITY_BASELINE.md`](../security/SECURITY_BASELINE.md).

## 3. Company scope

**Contract: company-scoped endpoints take the company from the `X-Company-Id` request header,
carrying the company UUID.**

```http
GET /api/v1/orders?page=0&size=50 HTTP/1.1
Authorization: Bearer <token>
X-Company-Id: 6f6a2b40-1f0a-4f7a-9b60-0d4a2a1c9d11
X-Correlation-Id: ui-order-list-8f31
```

Why a header rather than a path segment (`/companies/{id}/orders`) or a query parameter:

- **it does not multiply routes.** Every business resource would otherwise carry a company
  prefix in its path, and each of those prefixes is a place to forget the check;
- **it is uniform.** One header, one filter, one validation point - `CompanyScopeFilter` - so
  "where is the tenant validated" has exactly one answer;
- **it separates the tenant from the resource address.** A URL identifies *what*; the header
  says *in which company*. Nothing about a resource id changes when a caller switches company;
- **it is invisible to caching and logging by accident**, unlike a query parameter that ends up
  in access logs and browser history.

The cost - a header is easier to forget than a path segment - is paid by failing loudly: an
endpoint that needs a scope and does not get one answers 400 `company-scope-required` rather
than guessing a default company.

Rules:

1. the header **selects**; `tms.membership` **decides**. A company the caller holds no active
   membership in is refused with 403 `company-scope-forbidden`, before any controller runs;
2. the response for an unknown company id is identical to the response for another tenant's
   company. The API does not confirm that a company exists;
3. permissions are evaluated **inside the selected company**, never unioned across the caller's
   companies;
4. **no request body, query parameter or filter ever carries a company or organization id.**
   If one did, the tenant would be back in the client's hands. `PagingConventionsTest` asserts
   that the paging contract has no such field;
5. principal-scoped endpoints (`GET /api/v1/me`) need no header and ignore one if sent.

Which companies may be selected is answered by `GET /api/v1/me`.

## 4. Errors - RFC 9457

Every error, from a filter or a controller, is one `application/problem+json` document:

```json
{
  "type": "urn:tms:problem:company-scope-forbidden",
  "title": "Company scope is not allowed",
  "status": 403,
  "detail": "The selected company is not available to this account.",
  "instance": "/api/v1/companies/current",
  "code": "company-scope-forbidden",
  "timestamp": "2026-08-19T08:41:07.123Z",
  "correlationId": "ui-order-list-8f31"
}
```

- **branch on `type` or `code`, never on `detail`.** `detail` is human-readable prose and may be
  reworded; `type`/`code` are stable;
- `type` is a URN rather than an `https://` URL because the identifier must be stable and
  unambiguous, and RFC 9457 does not require it to be dereferenceable;
- `correlationId` matches the `X-Correlation-Id` response header and the server log lines for
  that request.

### 4.1 Catalogue

| `code` | Status | Meaning |
|---|---|---|
| `unauthenticated` | 401 | no credentials were sent |
| `invalid-token` | 401 | the token was not accepted (expired, forged, wrong issuer/audience, malformed) |
| `principal-not-provisioned` | 403 | valid token, no active TMS profile - an administrator must act |
| `company-scope-required` | 400 | the endpoint is company-scoped and `X-Company-Id` was missing |
| `company-scope-invalid` | 400 | `X-Company-Id` is not a UUID |
| `company-scope-forbidden` | 403 | the caller holds no active membership in the selected company |
| `access-denied` | 403 | authenticated and scoped, but the required permission is missing |
| `validation-failed` | 400 | Bean Validation rejected fields; see `errors` |
| `malformed-request` | 400 | unreadable body, wrong parameter type, unsupported sort, wrong method or media type |
| `resource-not-found` | 404 | no such resource **inside the caller's company scope** |
| `conflict` | 409 | a business invariant or optimistic-locking check refused the change |
| `idempotency-key-reused` | 409 | an `Idempotency-Key` was replayed with a **different** payload. Distinct from `conflict` because the caller's correct reaction is different in kind: not "reload and retry" but "your client sent two bodies under one key" |
| `storage-unavailable` | 503 | proof-of-delivery evidence was requested of a deployment with no object store configured (ADR-006). Nothing is broken and no retry helps until an administrator configures it |
| `feature-not-configured` | 503 | the generalisation of the above, for every other optional capability - today, outbound webhooks with no signing key (migration V35). Same reading: stop offering the feature rather than retrying |
| `internal-error` | 500 | unexpected failure; quote `correlationId` |

### 4.2 Validation failures

```json
{
  "type": "urn:tms:problem:validation-failed",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more fields are not valid.",
  "code": "validation-failed",
  "errors": [
    { "field": "code",      "message": "must not be blank" },
    { "field": "latitude",  "message": "must be between -90 and 90" }
  ]
}
```

`field` is the request-side name - a JSON path or a parameter name - never an entity or column
name.

### 4.3 What errors never contain

No stack trace, exception class, bean name, SQL fragment or internal path. A 500 says only that
the request failed and carries the correlation id; the cause is in the server log under that id.
`ApiSecurityTest.errorsDoNotLeakInternals` enforces this.

### 4.4 404 rather than 403 for another tenant's resource

Scoped repositories return nothing for a row belonging to another company, so the caller sees
404. Answering 403 would confirm that the id exists somewhere, which is itself a cross-tenant
leak.

## 5. Paging, sorting and filtering

List endpoints accept:

| Parameter | Default | Notes |
|---|---|---|
| `page` | `0` | zero-based |
| `size` | `25` | **clamped to 200 by the server**, never rejected |
| `sort` | none | `property,asc\|desc`, several separated by `;` |

```
GET /api/v1/origins?page=1&size=50&sort=code,asc;name,desc
```

Two rules are security rules rather than ergonomics:

1. **the page size is capped server-side.** An unbounded `size` is a denial of service against a
   database sized for 10,000+ orders/day. An oversized request is clamped and the response
   reports the size that was actually applied;
2. **sort properties are allow-listed per endpoint.** A sort property reaches an `ORDER BY`
   clause, which no ORM can parameterise; an unknown property is refused with 400
   `malformed-request`, never silently ignored.

Responses:

```json
{
  "content": [ ... ],
  "page": 1,
  "size": 50,
  "totalElements": 412
}
```

with `totalPages`, `hasNext` and `hasPrevious` derived. The envelope is defined by TMS rather
than by the persistence framework, so the contract does not change if the ORM does.
`totalElements` is always the count **inside the caller's company scope**.

Filters are explicit query parameters per endpoint (`?active=true&zoneId=...`). There is no
generic query language, and - see section 3 - no filter ever accepts a tenant id.

## 6. Correlation

- send `X-Correlation-Id` (or `X-Request-Id`) to trace a request; if absent, the backend
  generates one;
- it is always returned in the `X-Correlation-Id` response header and included in error
  documents as `correlationId`;
- it appears in every server log line for that request;
- a client-supplied value is sanitised to at most 64 characters of `[A-Za-z0-9._:-]`; anything
  else is replaced with a generated id, which blocks header injection and log forging.

## 7. Idempotency and concurrency

Not yet implemented; recorded so later modules do not invent their own:

- **updates** will carry an optimistic-locking version and answer 409 `conflict` on a stale
  write. This matters first for trip assignment (architecture section 9);
- **create** endpoints will accept an `Idempotency-Key` header when a retry must not produce a
  second row.

Both arrive with the module that needs them, with an ADR if they change this contract.

## 8. Conventions for new endpoints

1. path under `${tms.api.base-path}`; controller in an `..api..` package;
2. company-scoped -> take a `CompanyScope` parameter and carry `@PreAuthorize` naming a
   `Permission`;
3. the controller authorizes and delegates; the use case holds the rules; the repository holds
   the SQL. `LayeringTest` fails the build if a controller reaches a repository directly;
4. request records use Bean Validation; the response is a record, never an entity;
5. list endpoints take `PageQuery` and return `PageResponse`;
6. errors are thrown as the shared exceptions (`ResourceNotFoundException`,
   `InvalidRequestException`, ...) and shaped centrally - a controller never builds an error
   body;
7. ship a cross-tenant isolation test.
