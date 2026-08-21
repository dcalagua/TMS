package com.ebim.tms.integration.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * Create and update share one shape, following every other module's convention.
 *
 * <p>Three things a caller cannot say here, and each absence is a decision:
 *
 * <ul>
 *   <li><b>No company.</b> The credential is created inside the administrator's selected company
 *       scope. A body that named a company would be a client-supplied tenant.</li>
 *   <li><b>No secret.</b> It is generated server-side from a CSPRNG. Letting an administrator
 *       choose one would put a human-memorable string where 256 bits of entropy belong.</li>
 *   <li><b>No client id.</b> Also generated, so its shape and uniqueness are guaranteed rather
 *       than hoped for.</li>
 * </ul>
 *
 * @param scopes the codes of {@code IntegrationScope}. {@code @NotEmpty} on purpose: a credential
 *     that may do nothing is not a credential, it is an unused row that still authenticates
 * @param carrierId the carrier this credential answers for, or null (migration V31). The one thing
 *     on this record that names another tenant-scoped row, which is why the service resolves it
 *     through {@code CarrierLookupPort.findActiveInCompany} inside the administrator's own scope
 *     rather than trusting the id. Required when {@code scopes} contains
 *     {@code integration.tender:respond} and refused otherwise - a carrier bound to a credential
 *     that cannot answer tenders would be a field with no meaning, and the reverse would be a key
 *     allowed to answer tenders with no answer to "whose"
 */
public record IntegrationClientRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotEmpty(message = "a credential must hold at least one scope") Set<@NotBlank String> scopes,
        UUID carrierId) {

    public IntegrationClientRequest {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
