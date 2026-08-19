package com.ebim.tms.iam.api;

import com.ebim.tms.iam.application.MeService;
import com.ebim.tms.iam.application.MeView;
import com.ebim.tms.shared.security.TmsPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own identity and access.
 *
 * <p>Principal-scoped, not company-scoped: it is the call the frontend makes <em>before</em> a
 * company has been chosen, so it requires no {@code X-Company-Id} header and no permission
 * beyond being authenticated. A caller can only ever read their own record - the principal is
 * resolved from the validated token, and there is no user id parameter to substitute.
 */
@RestController
@RequestMapping("${tms.api.base-path}/me")
@Tag(name = "Me", description = "Identity and access of the signed-in user")
public class MeController {

    private final MeService meService;

    public MeController(MeService meService) {
        this.meService = meService;
    }

    @GetMapping
    @Operation(summary = "Profile, selectable companies and the permissions held in each")
    public MeView me(@AuthenticationPrincipal TmsPrincipal principal) {
        return meService.describe(principal);
    }
}
