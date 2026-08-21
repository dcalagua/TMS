package com.ebim.tms.tracking.infrastructure;

import com.ebim.tms.shared.reference.TrackingIntakePort;
import com.ebim.tms.shared.reference.TrackingIntakeResult;
import com.ebim.tms.shared.reference.TrackingReport;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.tracking.application.TrackingIngestionService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link TrackingIntakePort}: a one-line delegation to
 * {@link TrackingIngestionService}, which is where the rules are.
 *
 * <p>A pass-through adapter rather than making the service implement the port directly, following
 * {@code DriverLookupAdapter} over {@code VehicleLookupService}: the service is this module's API to
 * itself and may grow methods that have nothing to do with what another module is allowed to ask
 * for. The port is the contract, and keeping it on a separate class is what stops the two drifting
 * into one.
 */
@Component
class TrackingIntakeAdapter implements TrackingIntakePort {

    private final TrackingIngestionService ingestionService;

    TrackingIntakeAdapter(TrackingIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public List<TrackingIntakeResult> record(CompanyScope scope, List<TrackingReport> reports) {
        return ingestionService.record(scope, reports);
    }
}
