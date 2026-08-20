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
 */
package com.ebim.tms.integration;
