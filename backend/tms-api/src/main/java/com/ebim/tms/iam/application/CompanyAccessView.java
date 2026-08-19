package com.ebim.tms.iam.application;

import java.util.List;
import java.util.UUID;

/**
 * One company the caller may operate in, with the access they hold there.
 *
 * <p>Both lists are UX input, not grants. {@code permissions} are the fine-grained
 * {@code resource:action} codes the backend actually enforces; {@code capabilities} are the
 * coarse module names derived from them, which is what a menu or a toolbar keys on. Editing
 * either one in the browser changes nothing - every endpoint re-checks server-side, against
 * the company selected by the {@code X-Company-Id} header.
 */
public record CompanyAccessView(
        UUID id,
        String code,
        String name,
        String timeZone,
        OrganizationView organization,
        List<String> permissions,
        List<String> capabilities) {}
