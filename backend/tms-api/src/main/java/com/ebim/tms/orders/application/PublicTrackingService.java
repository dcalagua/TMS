package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.TrackingToken;
import com.ebim.tms.orders.infrastructure.TrackingTokenDirectory;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.security.CommercialAccessGate;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopeLoader;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The anonymous read of a customer tracking link (ADR-029 decision 3), in two steps on two connections:
 *
 * <ol>
 *   <li><b>Resolve</b> - the token's hash to {@code (link, company)} through {@link TrackingTokenDirectory},
 *       the one statement on the owner connection; then the company's scope through
 *       {@link CompanyScopeLoader} (empty for an inactive company or organization).</li>
 *   <li><b>Project</b> - under {@link PublicTrackingAuthentication}, so {@link PublicTrackingProjector}'s
 *       transaction runs as {@code tms_app} bound to that company - after the commercial gate (ADR-017; a
 *       suspended contract serves no link), which reads the company from that same binding.</li>
 * </ol>
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: a transaction opened here would take its connection
 * before the scope is installed and keep the owner role for the whole read.
 *
 * <p>Every failure is the same {@link ResourceNotFoundException} with the same sentence (T6): a malformed
 * token, an unknown one, a revoked or expired link, an inactive tenant and a suspended contract are
 * indistinguishable to the caller.
 */
@Service
public class PublicTrackingService {

    static final String NOT_FOUND = "This tracking link does not exist or is no longer available.";

    private final TrackingTokenDirectory directory;
    private final CompanyScopeLoader companyScopeLoader;
    private final ObjectProvider<CommercialAccessGate> commercialAccessGate;
    private final PublicTrackingProjector projector;

    public PublicTrackingService(TrackingTokenDirectory directory, CompanyScopeLoader companyScopeLoader,
            ObjectProvider<CommercialAccessGate> commercialAccessGate, PublicTrackingProjector projector) {
        this.directory = directory;
        this.companyScopeLoader = companyScopeLoader;
        this.commercialAccessGate = commercialAccessGate;
        this.projector = projector;
    }

    public PublicTrackingView view(String rawToken) {
        if (!TrackingToken.isWellFormed(rawToken)) {
            throw notFound();
        }
        TrackingTokenDirectory.ResolvedLink resolved = directory.resolve(TrackingToken.hashOf(rawToken))
                .orElseThrow(PublicTrackingService::notFound);
        CompanyScope scope = companyScopeLoader.loadActiveCompanyScope(resolved.companyId())
                .orElseThrow(PublicTrackingService::notFound);
        CommercialAccessGate gate = commercialAccessGate.getIfAvailable();
        Optional<PublicTrackingView> view = PublicTrackingAuthentication.runAs(scope, () -> {
            // Inside the scope: the gate reads "the current company" from the connection's tms.company_id,
            // exactly as it does for a signed-in request.
            if (gate != null && !gate.check(scope).allowed()) {
                return Optional.<PublicTrackingView>empty();
            }
            return projector.project(scope, resolved.linkId());
        });
        return view.orElseThrow(PublicTrackingService::notFound);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException(NOT_FOUND);
    }
}
