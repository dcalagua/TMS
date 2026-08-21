/**
 * Outbound and inbound integrations with external products, EWM by EBIM included.
 *
 * <p>Hard boundary: integration happens through APIs, events and explicit contracts with
 * external identifiers only. No shared internal tables and no cross-product foreign keys.
 *
 * <h2>Inbound API v1 (migration V18)</h2>
 *
 * <p>The machine-to-machine surface at {@code /integration/v1}, for locations/stores and transport
 * orders. Four properties define it and each one is enforced somewhere specific:
 *
 * <ul>
 *   <li><b>Independent of any browser session.</b> A partner authenticates with an
 *       {@code integration_client} credential on its own security chain
 *       ({@code security.IntegrationSecurityConfig}). No Supabase token is accepted here, and no
 *       integration credential is accepted anywhere else.</li>
 *   <li><b>Tenant-scoped by construction.</b> A credential belongs to exactly one company, so the
 *       tenant comes from the credential rather than from a header there would be no way to
 *       trust. RLS applies as well: the request runs as {@code tms_app} with that company
 *       published ({@code TenantScopedDataSource} via {@code MachineAuthentication}).</li>
 *   <li><b>Idempotent.</b> Business identity - the external reference - makes redelivery an update
 *       rather than a duplicate; the optional {@code Idempotency-Key} additionally replays the
 *       original response to a sender that never learned the outcome
 *       ({@code application.IntegrationRequestExecutor}).</li>
 *   <li><b>Auditable.</b> Every delivery leaves an {@code integration_request} row, written in its
 *       own transaction so a failure is recorded too.</li>
 * </ul>
 *
 * <p>This module writes no business data of its own. Locations and orders are created through
 * {@code LocationIntakePort} and {@code OrderIntakePort} in {@code shared.reference}, implemented
 * by {@code masterdata} and {@code orders} - both because {@code ModuleBoundaryTest} forbids a
 * direct dependency, and because an integration must not become a second, more permissive door
 * into a table the UI validates carefully.
 *
 * <p>Contract, examples and the credential threat model: {@code docs/integrations/INBOUND_API_V1.md}.
 *
 * <h2>Outbound: polling, and since V35, push</h2>
 *
 * <p>The outbound half rests on one table, {@code tms.shipment_outbox_event} (V20), written in the
 * same transaction as the trip state change it records. There are two ways to consume it and they
 * describe the same facts with the same identifiers:
 *
 * <ul>
 *   <li><b>Polling</b> - {@code GET /integration/v1/shipments/events?since=...}, authenticated with
 *       an integration credential holding {@code integration.shipment:read}. Unchanged since V20 and
 *       still the simplest thing for a partner who already runs a scheduler.</li>
 *   <li><b>Webhooks</b> (migration V35) - a company administrator registers an endpoint from the
 *       Integration Hub and TMS POSTs each selected event to it, signed with HMAC-SHA-256, retried
 *       on a published schedule, with every attempt recorded. The fan-out happens in the business
 *       transaction; the HTTP call never does. Contract, signature scheme and runbook:
 *       {@code docs/integrations/WEBHOOKS_V1.md}.</li>
 * </ul>
 *
 * <p>A webhook subscription is deliberately <em>not</em> an {@code integration_client}: a
 * credential is how a partner authenticates into TMS, and a subscription is an address the customer
 * owns. The receiving system may have no reason to ever call TMS at all.
 */
package com.ebim.tms.integration;
