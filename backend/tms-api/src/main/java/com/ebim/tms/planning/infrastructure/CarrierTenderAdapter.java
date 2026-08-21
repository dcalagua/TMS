package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.application.TripTenderService;
import com.ebim.tms.shared.reference.CarrierTenderOffer;
import com.ebim.tms.shared.reference.CarrierTenderPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code planning}'s side of {@link CarrierTenderPort} (migration V31): the only way
 * {@code com.ebim.tms.integration} reaches tendering, and therefore the only reason a carrier can
 * answer for itself without the two modules importing each other
 * ({@code ModuleBoundaryTest}).
 *
 * <p>A pass-through, deliberately. Every rule about what a carrier may answer, when, and what
 * happens to a lapsed offer lives in {@link TripTenderService}, so that the M2M path and the UI path
 * cannot diverge on any of it - which is the whole reason the two share a service rather than each
 * getting one. The port exists to keep the module boundary, not to hold logic.
 */
@Component
public class CarrierTenderAdapter implements CarrierTenderPort {

    private final TripTenderService tenderService;

    public CarrierTenderAdapter(TripTenderService tenderService) {
        this.tenderService = tenderService;
    }

    @Override
    public List<CarrierTenderOffer> findOpenOffers(CompanyScope scope, UUID carrierId, String shipmentNumber) {
        return tenderService.openOffers(scope, carrierId, shipmentNumber);
    }

    @Override
    public CarrierTenderOffer respond(CompanyScope scope, UUID carrierId, String shipmentNumber, boolean accepted,
            String notes, UUID integrationClientId) {
        return tenderService.respondAsCarrier(scope, carrierId, shipmentNumber, accepted, notes,
                integrationClientId);
    }
}
