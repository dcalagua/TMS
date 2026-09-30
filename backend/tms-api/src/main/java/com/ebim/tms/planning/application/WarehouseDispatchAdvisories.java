package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DispatchVerificationStatus;
import com.ebim.tms.planning.domain.ExternalDispatch;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.ExternalDispatchRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import com.ebim.tms.shared.settings.DispatchConfirmationMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The Control Tower's warehouse advisories (ADR-013 section 13): what a warehouse system said that a
 * person should look at. Derived on read from the stored documents; nothing here is a lifecycle, and
 * {@code trip_exception} - person-only - is deliberately not used for machine facts.
 *
 * <ul>
 *   <li>{@code DISPATCH_MISMATCH} - the current document of a trip of the day disagrees with the plan,
 *       or could not be applied (which is a disagreement between the warehouse and TMS's state);</li>
 *   <li>{@code AWAITING_WAREHOUSE_DISPATCH} - in {@code EXTERNAL_REQUIRED}, a committed trip is past
 *       its planned departure and no dispatch document has arrived;</li>
 *   <li>{@code EXTERNAL_DISPATCH_UNMATCHED} - a document named a shipment this company does not have.</li>
 * </ul>
 */
@Component
public class WarehouseDispatchAdvisories {

    private static final Set<TripStatus> AWAITING_STATES =
            EnumSet.of(TripStatus.CONFIRMED, TripStatus.READY_FOR_DISPATCH);

    private final ExternalDispatchRepository dispatches;
    private final TripRepository tripRepository;
    private final CompanySettingsPort companySettings;

    public WarehouseDispatchAdvisories(ExternalDispatchRepository dispatches, TripRepository tripRepository,
            CompanySettingsPort companySettings) {
        this.dispatches = dispatches;
        this.tripRepository = tripRepository;
        this.companySettings = companySettings;
    }

    List<ControlTowerAdvisoryView> advisories(CompanyScope scope, LocalDate date, OffsetDateTime now, int limit) {
        List<Trip> tripsOfDay = tripRepository.findByCompanyIdAndPlanningDateAndStatusIn(scope.companyId(), date,
                EnumSet.allOf(TripStatus.class), PageRequest.of(0, 1000));
        Map<UUID, Trip> byId = tripsOfDay.stream()
                .collect(Collectors.toMap(Trip::id, Function.identity(), (first, second) -> first));
        List<ExternalDispatch> current = byId.isEmpty()
                ? List.of()
                : dispatches.findCurrentByTripIds(scope.companyId(), byId.keySet());

        List<ControlTowerAdvisoryView> advisories = new ArrayList<>();
        for (ExternalDispatch document : current) {
            if (document.verificationStatus() == DispatchVerificationStatus.MISMATCH) {
                Trip trip = byId.get(document.tripId());
                advisories.add(new ControlTowerAdvisoryView(ControlTowerAdvisoryView.AdvisoryType.DISPATCH_MISMATCH,
                        document.tripId(), trip == null ? null : trip.shipmentNumber(), document.id(), null, null,
                        "Warehouse dispatch " + document.dispatchReference() + " (" + document.outcome().name()
                                + ") disagrees with the plan."));
            }
        }

        if (companySettings.settingsOf(scope.companyId()).dispatchConfirmationMode()
                == DispatchConfirmationMode.EXTERNAL_REQUIRED) {
            Set<UUID> documented = current.stream().map(ExternalDispatch::tripId).collect(Collectors.toSet());
            for (Trip trip : tripsOfDay) {
                if (AWAITING_STATES.contains(trip.status()) && trip.plannedDepartureAt() != null
                        && trip.plannedDepartureAt().isBefore(now) && !documented.contains(trip.id())) {
                    advisories.add(new ControlTowerAdvisoryView(
                            ControlTowerAdvisoryView.AdvisoryType.AWAITING_WAREHOUSE_DISPATCH, trip.id(),
                            trip.shipmentNumber(), null, null, null,
                            "Planned to leave at " + trip.plannedDepartureAt() + "; the warehouse has not confirmed "
                                    + "the dispatch."));
                }
            }
        }

        OffsetDateTime startOfDay = date.atStartOfDay(scope.zoneId()).toOffsetDateTime();
        for (ExternalDispatch document : dispatches.findCurrentUnmatchedSince(scope.companyId(), startOfDay)) {
            advisories.add(new ControlTowerAdvisoryView(ControlTowerAdvisoryView.AdvisoryType.EXTERNAL_DISPATCH_UNMATCHED,
                    null, document.transportReference(), document.id(), null, null,
                    "Warehouse dispatch " + document.dispatchReference() + " names shipment "
                            + document.transportReference() + ", which does not exist in this company."));
        }
        return advisories.size() <= limit ? List.copyOf(advisories) : List.copyOf(advisories.subList(0, limit));
    }
}
