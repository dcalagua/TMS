package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationClientRequest;
import com.ebim.tms.integration.application.IntegrationClientSecretView;
import com.ebim.tms.integration.application.IntegrationClientService;
import com.ebim.tms.integration.application.IntegrationClientView;
import com.ebim.tms.integration.application.IntegrationRequestView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration of integration credentials, on the <em>user-facing</em> API.
 *
 * <p>Note the path: {@code /api/v1/integration-clients}, not {@code /integration/v1}. Managing
 * credentials is an administrator's job done from the browser with a Supabase token, and an
 * integration credential cannot reach these endpoints at all - it is on a different security chain
 * and its {@code CompanyScope} carries no permissions, so {@code hasAuthority('integration.client:manage')}
 * can never be satisfied by one. A partner key that could mint another partner key would make
 * revocation meaningless.
 *
 * <p>There is no delete. A credential is revoked, which keeps its inbox history intact and
 * answerable - the same "deactivate, never delete" rule every master in TMS follows.
 */
@RestController
@RequestMapping("${tms.api.base-path}/integration-clients")
@Tag(name = "Integration credentials",
        description = "Issue, rotate and revoke the machine credentials partners authenticate with")
public class IntegrationClientController {

    private final IntegrationClientService clientService;

    public IntegrationClientController(IntegrationClientService clientService) {
        this.clientService = clientService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('integration.client:read')")
    @Operation(summary = "List the integration credentials of the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<IntegrationClientView> list(CompanyScope scope, @ModelAttribute PageQuery pageQuery) {
        return clientService.list(scope, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('integration.client:read')")
    @Operation(summary = "Get one integration credential")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public IntegrationClientView get(CompanyScope scope, @PathVariable UUID id) {
        return clientService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('integration.client:manage')")
    @Operation(summary = "Issue a new integration credential",
            description = "The only response that ever contains the secret. It is not stored and "
                    + "cannot be retrieved afterwards; a lost secret is replaced by rotating.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public IntegrationClientSecretView create(CompanyScope scope, @Valid @RequestBody IntegrationClientRequest request) {
        return clientService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('integration.client:manage')")
    @Operation(summary = "Rename an integration credential or change its scopes",
            description = "Never changes the secret; use rotate for that.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public IntegrationClientView update(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody IntegrationClientRequest request) {
        return clientService.update(scope, id, request);
    }

    @PostMapping("/{id}/rotate")
    @PreAuthorize("hasAuthority('integration.client:manage')")
    @Operation(summary = "Issue a new secret for an existing credential",
            description = "The superseded secret keeps working for the grace window, so the partner "
                    + "can redeploy without a coordinated cutover. Pass graceHours=0 when the old "
                    + "secret may have leaked.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public IntegrationClientSecretView rotate(CompanyScope scope, @PathVariable UUID id,
            @RequestParam(required = false) Integer graceHours) {
        return clientService.rotate(scope, id, graceHours);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('integration.client:manage')")
    @Operation(summary = "Revoke a credential immediately and permanently",
            description = "No grace window: both the current and any superseded secret stop working "
                    + "at once. Revocation is terminal; issue a new credential instead of undoing it.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public IntegrationClientView revoke(CompanyScope scope, @PathVariable UUID id) {
        return clientService.revoke(scope, id);
    }

    @GetMapping("/requests")
    @PreAuthorize("hasAuthority('integration.client:read')")
    @Operation(summary = "The integration inbox: inbound deliveries received by this company",
            description = "Newest first. Filter by clientId to see one partner's traffic. Carries "
                    + "no payload content, only what was delivered and what happened to it.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<IntegrationRequestView> requests(CompanyScope scope,
            @RequestParam(required = false) UUID clientId, @ModelAttribute PageQuery pageQuery) {
        return clientService.history(scope, clientId, pageQuery);
    }
}
