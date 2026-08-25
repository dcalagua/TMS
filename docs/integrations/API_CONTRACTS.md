# TMS by EBIM - API contract register

Every published interface, what it is for, where its contract is written, and what would count as a
breaking change to it. Written for job 13's audit of the integration surface; kept as the index a
partner conversation starts from.

If an interface is not in this table, it is not published, and a partner depending on it is
depending on an implementation detail.

---

## 1. The two surfaces

| | Application API | Integration API |
|---|---|---|
| Base path | `/api/v1` (`tms.api.base-path`) | `/integration/v1` |
| Audience | The TMS browser client | Partner machines: ERP, WMS, store systems, telematics feeds, carriers |
| Authentication | Supabase JWT + `X-Company-Id`, membership validated server-side | `Authorization: Bearer <clientId>.<secret>` (`tms.integration_client`) |
| Tenant | The validated header | A property of the credential; there is no header to get wrong |
| Release cadence | Ships with the backend | The partner's, which is why the paths are deliberately independent |
| Security chain | `SecurityConfig` | `IntegrationSecurityConfig`, a separate chain over the whole prefix |

Neither credential is accepted on the other surface. An integration credential's `CompanyScope`
carries no permissions at all, so no `@PreAuthorize` on the application API can ever be satisfied by
one - which is what makes "a partner key cannot mint another partner key" structural rather than a
rule somebody remembered to write.

Both are declared in the OpenAPI document (`/v3/api-docs`, Swagger UI at `/swagger-ui.html`): the
JWT scheme is the global requirement, and every `/integration/v1` controller overrides it with the
`integrationCredential` scheme, so "try it out" reflects what the filter chain will actually do.

---

## 2. Inbound contracts (partner → TMS)

| Contract | Endpoints | Scope | Documented in |
|---|---|---|---|
| **Locations** | `POST /integration/v1/locations`, `POST /integration/v1/locations/batch` | `integration.location:write` | `INBOUND_API_V1.md` |
| **Orders** | `POST /integration/v1/orders`, `POST /integration/v1/orders/batch` | `integration.order:write` | `INBOUND_API_V1.md` |
| **Tracking** | `POST /integration/v1/tracking/positions` | `integration.tracking:write` | `../domain/TRACKING_V1.md`, ADR-007 |
| **Tender responses** | `GET`/`POST /integration/v1/tenders/…` | `integration.tender:respond` | `../domain/CARRIER_TENDERING_V1.md` |
| **Credential self-check** | `GET /integration/v1/ping` | *(authenticated only)* | `INBOUND_API_V1.md` |

Every write is idempotent twice over: by business identity - the external reference makes a
redelivery an update rather than a duplicate - and, optionally, by `Idempotency-Key`, which replays
the original response to a sender that never learned the outcome. Repeating a key with a *different*
payload is refused with `urn:tms:problem:idempotency-key-reused`, because answering it with the first
body would hide a client bug.

Every delivery leaves a row in `tms.integration_request`, written in its own transaction so a failed
one is recorded too. It holds no credential material and, by default, no raw payload.

---

## 3. Outbound contracts (TMS → partner)

| Contract | Mechanism | Scope / permission | Documented in |
|---|---|---|---|
| **Shipments** | `GET /integration/v1/shipments`, `/shipments/{id}` | `integration.shipment:read` | `OUTBOUND_SHIPMENT_V1.md` |
| **Execution events - pull** | `GET /integration/v1/shipments/events?since=…` | `integration.shipment:read` | `OUTBOUND_SHIPMENT_V1.md` §6 |
| **Execution events - push** | `POST` to a registered endpoint, signed and retried | `integration.webhook:manage` to configure | `WEBHOOKS_V1.md` |
| **Tender offers** | `GET /integration/v1/tenders` | `integration.tender:respond` | `../domain/CARRIER_TENDERING_V1.md` |

The pull and push mechanisms read the **same** transactional outbox (`tms.shipment_outbox_event`,
migration V20) and use the same event ids, so a partner can run both during a cutover without a gap
or a duplicate. Neither depends on the other continuing to work.

---

## 4. Cross-cutting rules

**Versioning.** Both surfaces are versioned in the path. Within a version, fields may be added and a
client must ignore what it does not recognise; a field is never removed or given a new meaning. A
breaking change is a new version served alongside the old one, not an edit to this one. The webhook
envelope repeats its version in the body, because receivers store deliveries and read them back
later, when the URL is gone.

**Errors.** Every failure is an RFC 9457 `application/problem+json` document whose `type`
(`urn:tms:problem:<code>`) and `code` are stable. Clients branch on those, never on `detail`, which
is human prose and may be reworded. The catalogue is `shared.api.ProblemType`; see
`../api/API_CONVENTIONS.md`.

**Correlation id.** Every request may carry `X-Correlation-Id` (or `X-Request-Id`, accepted as an
alias because most gateways already emit it). It is sanitised, echoed on the response, written to
every log line of the request, and included in the error document - so one value quoted in a support
ticket is findable on both sides. It is generated when absent. Since migration V35 the webhook
dispatcher runs each pass under a correlation id of its own and sends it to the receiver, so
background work is traceable on the same terms as a request.

**Enum values.** A partner's deserialiser must not reject an unknown enum value on sight. Reserved
values exist in several vocabularies precisely so that the day something starts producing them is not
a breaking change - `SHIPMENT_CHANGED` is the standing example.

**Scopes are closed.** Adding an integration capability takes a migration *and* an enum constant,
deliberately, so a new capability cannot be granted by a typo in a payload.

---

## 5. Administration of the surface

Both halves are configured from the **Integration Hub** (`/settings/integrations`), which is a
browser screen on the application API and is not reachable with an integration credential.

| Object | Endpoints | Permissions |
|---|---|---|
| Credentials and the inbound inbox | `/api/v1/integration-clients…` | `integration.client:read` / `:manage` |
| Webhook endpoints and the delivery log | `/api/v1/webhooks…` | `integration.webhook:read` / `:manage` |

They are separate permissions because they fail in opposite directions: a credential is a way *in*,
a subscription is a way *out*. Both go to `ORGANIZATION_ADMIN` and `COMPANY_ADMIN` today; the split
exists so a deployment can later hand them to different people without a migration.

Nothing in either screen ever renders a usable secret. A credential's secret is hashed and
unrecoverable; a webhook's is encrypted because an HMAC cannot be computed from a hash
(`WEBHOOKS_V1.md` §5), and only its last four characters are ever shown.

---

## 6. Deliberately not published

- **No GraphQL, no Supabase Data API access.** Application objects live in the `tms` schema, which
  the Data API does not see (ADR-004).
- **No direct database access for partners**, and no shared tables with EWM or any other product.
- **No bulk export endpoint.** Reporting is a browser screen; a partner needing history pages the
  shipment API.
- **No inbound signature verification.** The inbound API authenticates with a credential, which is
  simpler for a partner to implement correctly than a signing scheme.
