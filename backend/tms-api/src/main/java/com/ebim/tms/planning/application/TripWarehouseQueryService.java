package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DispatchVerificationStatus;
import com.ebim.tms.planning.domain.ExternalDispatch;
import com.ebim.tms.planning.domain.ExternalDispatchOrder;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.infrastructure.ExternalDispatchOrderRepository;
import com.ebim.tms.planning.infrastructure.ExternalDispatchRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.WarehouseMilestoneRepository;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads for the trip workspace's warehouse card and for the raw document behind it. */
@Service
public class TripWarehouseQueryService {

    private final TripRepository tripRepository;
    private final ExternalDispatchRepository dispatches;
    private final ExternalDispatchOrderRepository dispatchOrders;
    private final WarehouseMilestoneRepository milestones;
    private final CompanySettingsPort companySettings;
    private final ExternalDispatchService externalDispatchService;

    public TripWarehouseQueryService(TripRepository tripRepository, ExternalDispatchRepository dispatches,
            ExternalDispatchOrderRepository dispatchOrders, WarehouseMilestoneRepository milestones,
            CompanySettingsPort companySettings, ExternalDispatchService externalDispatchService) {
        this.tripRepository = tripRepository;
        this.dispatches = dispatches;
        this.dispatchOrders = dispatchOrders;
        this.milestones = milestones;
        this.companySettings = companySettings;
        this.externalDispatchService = externalDispatchService;
    }

    @Transactional(readOnly = true)
    public TripWarehouseView forTrip(CompanyScope scope, UUID tripId) {
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        List<ExternalDispatch> documents = dispatches.findByCompanyIdAndTripIdOrderByReceivedAtDesc(scope.companyId(),
                trip.id());
        Map<UUID, List<ExternalDispatchOrder>> ordersByDocument = documents.isEmpty()
                ? Map.of()
                : dispatchOrders.findByCompanyIdAndExternalDispatchIdIn(scope.companyId(),
                        documents.stream().map(ExternalDispatch::id).toList()).stream()
                        .collect(Collectors.groupingBy(ExternalDispatchOrder::externalDispatchId));

        return new TripWarehouseView(trip.id(), trip.shipmentNumber(),
                companySettings.settingsOf(scope.companyId()).dispatchConfirmationMode().name(),
                trip.dispatchSource() == null ? null : trip.dispatchSource().name(), trip.actualDepartureAt(),
                derivedVerification(documents).name(),
                documents.stream().map(document -> toView(document, ordersByDocument.getOrDefault(document.id(), List.of())))
                        .toList(),
                milestones.findByCompanyIdAndTripIdOrderByOccurredAtAsc(scope.companyId(), trip.id()).stream()
                        .map(milestone -> new TripWarehouseView.Milestone(milestone.milestoneType(), milestone.eventId(),
                                milestone.loadReference(), milestone.warehouseCode(), milestone.occurredAt(),
                                milestone.receivedAt()))
                        .toList());
    }

    /** The document exactly as received, for a person who needs to read what the warehouse sent. */
    @Transactional(readOnly = true)
    public String rawDocument(CompanyScope scope, UUID tripId, UUID documentId) {
        return dispatches.findByIdAndCompanyId(documentId, scope.companyId())
                .filter(document -> tripId.equals(document.tripId()))
                .map(ExternalDispatch::rawPayload)
                .orElseThrow(() -> new ResourceNotFoundException("Dispatch document not found."));
    }

    /** ADR-013 section 2: a trip's verification is derived from its current documents, never stored. */
    static DispatchVerificationStatus derivedVerification(List<ExternalDispatch> documents) {
        List<ExternalDispatch> current = documents.stream().filter(ExternalDispatch::isCurrent).toList();
        if (current.isEmpty()) {
            return DispatchVerificationStatus.UNVERIFIED;
        }
        if (current.stream().anyMatch(document -> document.verificationStatus() == DispatchVerificationStatus.MISMATCH)) {
            return DispatchVerificationStatus.MISMATCH;
        }
        return current.stream().anyMatch(document -> document.verificationStatus() == DispatchVerificationStatus.OVERRIDDEN)
                ? DispatchVerificationStatus.OVERRIDDEN
                : DispatchVerificationStatus.MATCHED;
    }

    private TripWarehouseView.Document toView(ExternalDispatch document, List<ExternalDispatchOrder> orders) {
        return new TripWarehouseView.Document(document.id(), document.sourceSystem(), document.dispatchReference(),
                document.revision(), document.isCurrent(), document.outcome().name(),
                document.verificationStatus().name(), document.loadReference(), document.warehouseCode(),
                document.actualDispatchAt(), document.carrierCode(), document.carrierName(),
                document.vehicleLicensePlate(), document.driverName(), document.driverDocumentNumber(),
                document.sealNumber(), document.transportDocumentNumber(), document.totalHandlingUnits(),
                document.totalWeightKg(), document.totalVolumeM3(), document.receivedAt(),
                externalDispatchService.fromJson(document.discrepancies()),
                orders.stream().map(order -> new TripWarehouseView.Order(order.orderId(), order.externalSource(),
                        order.externalReference(), order.warehouseOrderNumber(), order.status(), order.handlingUnits(),
                        order.weightKg(), order.volumeM3(), order.matchResult().name())).toList());
    }
}
