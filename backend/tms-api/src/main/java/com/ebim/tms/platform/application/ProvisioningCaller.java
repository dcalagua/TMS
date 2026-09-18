package com.ebim.tms.platform.application;

/**
 * The verified identity behind a provisioning call, as the application layer needs it.
 *
 * <p>{@code subject} and {@code jti} come from the token's own claims and are verified: the
 * signature covers them. {@code actorId} and {@code actorRole} are what MasterAdmin says about the
 * human who clicked the button, and they are <strong>audit only</strong> - nothing in TMS reads
 * them to decide whether a call is allowed. Authorisation comes from the scope, and from nowhere
 * else. Recording them anyway is what lets a tenant that appeared last Tuesday be traced back to a
 * person as well as to a key.
 *
 * <p>A record rather than the security framework's own type, so the use case stays free of
 * Spring Security and can be tested without one.
 */
public record ProvisioningCaller(String subject, String jti, String actorId, String actorRole) {
}
