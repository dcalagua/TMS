package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.domain.IntegrationScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Who am I, and does my key work?" - the first call a partner makes and the one they come back to
 * whenever something looks wrong.
 *
 * <p>It exists because the alternative is debugging authentication by posting real orders. It
 * writes nothing, requires no scope beyond being authenticated at all, and returns only facts the
 * holder of the credential already knows: their own client id, the company they are bound to and
 * the scopes they hold. Nothing about other credentials, nothing about other companies, and
 * nothing about the secret.
 */
@RestController
@RequestMapping(IntegrationApiPaths.V1)
@Tag(name = "Integration v1 - Identity", description = "Credential self-check")
public class IntegrationIdentityController {

    @GetMapping("/ping")
    @Operation(summary = "Verify the credential and report what it may do",
            description = "Writes nothing. Use it to confirm connectivity, the credential and the "
                    + "company binding before sending real data.")
    public IntegrationIdentity ping(IntegrationPrincipal principal) {
        return new IntegrationIdentity(
                principal.clientId(),
                principal.name(),
                principal.companyScope().companyCode(),
                principal.companyScope().companyName(),
                principal.companyScope().timeZone(),
                principal.scopes().stream().map(IntegrationScope::code).sorted().toList());
    }

    /**
     * Deliberately identifies the company by {@code code}, not by uuid. A partner speaks in codes
     * everywhere else in this API, and publishing internal identifiers to an external system
     * invites them to be used as keys - which then have to keep working forever.
     */
    public record IntegrationIdentity(
            String clientId,
            String name,
            String companyCode,
            String companyName,
            String companyTimeZone,
            List<String> scopes) {
    }
}
