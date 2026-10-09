package com.ebim.tms.iam.entitlements.application;

import com.ebim.tms.iam.entitlements.domain.EntitlementRejection;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Single-use {@code jti} on the entitlements routes (CCP spec section 8.1).
 *
 * <p>The M2M decoder already demands a {@code jti}, a signature, {@code iss}, {@code aud} and a short
 * lifetime; what it does not do is remember the {@code jti}, on purpose, because tenant creation reuses
 * one token across its own transport retries ({@code PlatformM2mJwtDecoders}). That stays as it is
 * (INV-1). Here, where MasterAdmin signs a fresh token per request, a second use is a replay.
 */
@Service
public class JtiGuard {

    private final EntitlementStore store;

    public JtiGuard(EntitlementStore store) {
        this.store = store;
    }

    public void consume(Call call) {
        if (call.jti() == null || !store.consumeJti(call.issuer(), call.jti(), call.rememberUntil())) {
            throw new EntitlementRejection(EntitlementRejection.Code.JTI_REPLAYED, "jti already used");
        }
    }

    /**
     * The verified caller.
     *
     * @param rememberUntil how long the {@code jti} must be remembered: at least as long as a token
     *                      carrying it could still be accepted
     */
    public record Call(String issuer, String subject, String jti, Instant rememberUntil) {}
}
