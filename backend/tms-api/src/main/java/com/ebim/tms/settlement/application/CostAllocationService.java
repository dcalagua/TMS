package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.AllocationCostSource;
import com.ebim.tms.settlement.domain.AllocationTrigger;
import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.CarrierInvoiceLine;
import com.ebim.tms.settlement.domain.CostAllocationLine;
import com.ebim.tms.settlement.domain.CostAllocationRun;
import com.ebim.tms.settlement.domain.CostAllocator;
import com.ebim.tms.settlement.infrastructure.CarrierInvoiceRepository;
import com.ebim.tms.settlement.infrastructure.CostAllocationRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderReferencePort;
import com.ebim.tms.shared.reference.OrderReferencePort.OrderCostReference;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.TripCostLookupPort;
import com.ebim.tms.shared.reference.TripCostLookupPort.TripCostSummary;
import com.ebim.tms.shared.reference.TripSettlementLookupPort;
import com.ebim.tms.shared.reference.TripSettlementLookupPort.TripAllocationSource;
import com.ebim.tms.shared.reference.TripSettlementLookupPort.TripLoad;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import com.ebim.tms.shared.settings.CostAllocationBasis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates a trip's cost to its orders and keeps every version (migration V68, ADR-026).
 *
 * <h2>Which cost</h2>
 *
 * <p>In order of preference: what the carrier's authorised documents billed for the trip (net of
 * VAT, ADR-024/ADR-027), else the closed actual, else the estimate. A trip with none of the three is not
 * allocated - an allocation of nothing would be a report of zero cost, which is the invented figure
 * ADR-026 forbids. The run says which figure it split.
 *
 * <h2>Versions</h2>
 *
 * <p>A new allocation supersedes the current run and inserts the next version, in one transaction
 * holding the current run's row lock; two nodes racing on a trip with no run yet collide on
 * {@code uq_trip_cost_allocation_run_version}. An automatic trigger whose allocation would come out
 * identical to the current one writes nothing; a person's "Recalcular" always writes a version, because
 * the person asked and the trail should say so.
 */
@Service
public class CostAllocationService {

    private final CostAllocationRepository repository;
    private final CarrierInvoiceRepository invoiceRepository;
    private final TripSettlementLookupPort tripLookupPort;
    private final TripCostLookupPort tripCostLookupPort;
    private final OrderReferencePort orderReferencePort;
    private final DestinationLookupPort destinationLookupPort;
    private final RouteTemplateLookupPort routeLookupPort;
    private final CompanySettingsPort companySettingsPort;
    private final AuditRecorder auditRecorder;
    private final Clock clock;
    private final CostAllocationBasis defaultBasis;

    public CostAllocationService(CostAllocationRepository repository, CarrierInvoiceRepository invoiceRepository,
            TripSettlementLookupPort tripLookupPort, TripCostLookupPort tripCostLookupPort,
            OrderReferencePort orderReferencePort, DestinationLookupPort destinationLookupPort,
            RouteTemplateLookupPort routeLookupPort, CompanySettingsPort companySettingsPort,
            AuditRecorder auditRecorder, Clock clock,
            @Value("${tms.finance.cost-allocation.default-basis:WEIGHT}") CostAllocationBasis defaultBasis) {
        this.repository = repository;
        this.invoiceRepository = invoiceRepository;
        this.tripLookupPort = tripLookupPort;
        this.tripCostLookupPort = tripCostLookupPort;
        this.orderReferencePort = orderReferencePort;
        this.destinationLookupPort = destinationLookupPort;
        this.routeLookupPort = routeLookupPort;
        this.companySettingsPort = companySettingsPort;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
        this.defaultBasis = defaultBasis;
    }

    /** The company's basis, else the deployment's. */
    public CostAllocationBasis basisOf(UUID companyId) {
        CostAllocationBasis basis = companySettingsPort.settingsOf(companyId).costAllocationBasis();
        return basis == null ? defaultBasis : basis;
    }

    // ------------------------------------------------------------------ writing

    /** A person's "Recalcular": always a new version, or a 409 that says why there can be none. */
    @Transactional
    public CostAllocationView recalculate(CompanyScope scope, UUID tripId, UUID actorId) {
        TripAllocationSource trip = tripLookupPort.findAllocationSource(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        if (trip.cancelled()) {
            throw new ConflictException("A cancelled trip is not allocated.");
        }
        if (allocate(scope, trip, AllocationTrigger.MANUAL, actorId).isEmpty()) {
            throw new ConflictException("The trip has no cost to allocate: no approved invoice, no closed actual"
                    + " and no estimate.");
        }
        return view(scope, tripId);
    }

    /**
     * The trips an approved document billed, in the approval's own transaction (ADR-026 section 5).
     * A note re-allocates too: it changes what the trip was billed.
     */
    @Transactional
    public int allocateApprovedInvoice(CompanyScope scope, CarrierInvoice invoice, UUID actorId) {
        // The approval's status change must be visible to the plain-SQL sum of authorised documents.
        invoiceRepository.flush();
        Set<UUID> trips = invoice.lines().stream().map(CarrierInvoiceLine::tripId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        int allocated = 0;
        for (UUID tripId : trips) {
            Optional<TripAllocationSource> trip = tripLookupPort.findAllocationSource(tripId, scope.companyId());
            if (trip.isPresent() && !trip.get().cancelled()
                    && allocate(scope, trip.get(), AllocationTrigger.INVOICE_APPROVED, actorId).isPresent()) {
                allocated++;
            }
        }
        return allocated;
    }

    /**
     * The sweep's call for one trip, in a transaction of its own.
     *
     * @return true when a new version was written
     */
    @Transactional
    public boolean allocateBySweep(CompanyScope scope, UUID tripId, AllocationTrigger trigger) {
        Optional<TripAllocationSource> trip = tripLookupPort.findAllocationSource(tripId, scope.companyId());
        if (trip.isEmpty() || trip.get().cancelled()) {
            return false;
        }
        Optional<CostAllocationRun> before = repository.findCurrentForUpdate(scope.companyId(), tripId);
        Optional<CostAllocationRun> after = allocate(scope, trip.get(), trigger, null);
        return after.isPresent() && (before.isEmpty() || !before.get().id().equals(after.get().id()));
    }

    /**
     * Allocates the trip, superseding its current run.
     *
     * @return the current run afterwards - a new one, or the unchanged one when an automatic trigger
     *         found nothing different; empty when the trip has no cost at all
     */
    Optional<CostAllocationRun> allocate(CompanyScope scope, TripAllocationSource trip, AllocationTrigger trigger,
            UUID actorId) {
        UUID companyId = scope.companyId();
        Optional<CostAllocationRun> current = repository.findCurrentForUpdate(companyId, trip.tripId());
        Optional<ResolvedCost> cost = resolveCost(companyId, trip.tripId());
        if (cost.isEmpty()) {
            return Optional.empty();
        }
        CostAllocationBasis basis = basisOf(companyId);

        Map<UUID, OrderCostReference> orders = orderReferencePort.costReferencesOf(
                trip.loads().stream().map(TripLoad::orderId).collect(Collectors.toSet()), companyId);
        Set<UUID> destinationIds = orders.values().stream().map(OrderCostReference::destinationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, MasterReference> destinations = destinationIds.isEmpty() ? Map.of()
                : destinationLookupPort.findAllInCompany(destinationIds, companyId);

        List<CostAllocator.Load> loads = new ArrayList<>();
        for (TripLoad load : trip.loads()) {
            OrderCostReference order = orders.get(load.orderId());
            loads.add(new CostAllocator.Load(load.assignmentId(), order == null ? null : order.destinationId(),
                    load.weightKg(), load.volumeM3(), load.pallets(), load.floorAreaM2()));
        }
        CostAllocator.Result result = CostAllocator.allocate(cost.get().amount(), basis, loads);

        if (current.isPresent() && trigger != AllocationTrigger.MANUAL
                && sameAs(companyId, current.get(), basis, cost.get(), result)) {
            return current;
        }

        Map<UUID, CostAllocator.Allocation> byAssignment = new HashMap<>();
        result.allocations().forEach(allocation -> byAssignment.put(allocation.assignmentId(), allocation));
        List<CostAllocationLine> lines = new ArrayList<>();
        for (TripLoad load : trip.loads()) {
            OrderCostReference order = orders.get(load.orderId());
            UUID destinationId = order == null ? null : order.destinationId();
            MasterReference destination = destinationId == null ? null : destinations.get(destinationId);
            CostAllocator.Allocation allocation = byAssignment.get(load.assignmentId());
            lines.add(new CostAllocationLine(load.orderId(), load.assignmentId(),
                    order == null ? load.orderId().toString() : order.orderNumber(),
                    order == null ? null : customerLabel(order), destinationId, label(destination),
                    load.weightKg(), load.volumeM3(), load.pallets(), load.floorAreaM2(),
                    allocation.basisQuantity(), allocation.share(), allocation.amount(),
                    allocation.allocated() ? null : "NO_BASIS"));
        }

        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        current.ifPresent(run -> repository.supersede(companyId, run.id(), now));
        int version = Math.max(current.map(CostAllocationRun::version).orElse(0),
                repository.lastVersion(companyId, trip.tripId())) + 1;
        String routeLabel = null;
        if (trip.routeId() != null) {
            RouteTemplate route = routeLookupPort.findAllInCompany(Set.of(trip.routeId()), companyId).get(trip.routeId());
            routeLabel = route == null ? null : route.code() + " - " + route.name();
        }
        CostAllocationRun run = new CostAllocationRun(UUID.randomUUID(), trip.tripId(), version, basis,
                cost.get().source(), cost.get().currency(), cost.get().amount(), result.allocatedAmount(),
                trip.loads().size(), result.unallocatedCount(), result.reason(), serviceDate(scope, trip),
                trip.shipmentNumber(), trip.carrierId(), trip.routeId(), routeLabel, now, actorId, trigger, null);
        repository.insert(companyId, run, lines);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", version);
        details.put("basis", basis.name());
        details.put("costSource", run.costSource().name());
        details.put("currency", run.currency());
        details.put("costAmount", run.costAmount().toPlainString());
        details.put("unallocatedOrderCount", run.unallocatedOrderCount());
        details.put("trigger", trigger.name());
        if (trip.shipmentNumber() != null) {
            details.put("shipmentNumber", trip.shipmentNumber());
        }
        auditRecorder.record(scope, AuditAggregateType.TRIP, trip.tripId(), AuditAction.COST_ALLOCATED, details);
        return Optional.of(run);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public CostAllocationView view(CompanyScope scope, UUID tripId) {
        if (tripLookupPort.findAllocationSource(tripId, scope.companyId()).isEmpty()) {
            throw new ResourceNotFoundException("Trip not found.");
        }
        List<CostAllocationRun> versions = repository.findVersions(scope.companyId(), tripId);
        CostAllocationView.RunView current = versions.stream().filter(CostAllocationRun::current).findFirst()
                .map(run -> CostAllocationView.RunView.of(run, repository.findLines(scope.companyId(), run.id())))
                .orElse(null);
        return new CostAllocationView(tripId, current,
                versions.stream().map(run -> CostAllocationView.RunView.of(run, List.of())).toList());
    }

    /** One version, with its rows: the earlier versions stay readable as they were. */
    @Transactional(readOnly = true)
    public CostAllocationView.RunView version(CompanyScope scope, UUID tripId, int version) {
        CostAllocationRun run = repository.findVersion(scope.companyId(), tripId, version)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation version not found."));
        return CostAllocationView.RunView.of(run, repository.findLines(scope.companyId(), run.id()));
    }

    // ------------------------------------------------------------------ helpers

    /** The figure to split, and where it came from. */
    record ResolvedCost(AllocationCostSource source, BigDecimal amount, String currency) {
    }

    Optional<ResolvedCost> resolveCost(UUID companyId, UUID tripId) {
        TripCostSummary cost = tripCostLookupPort.findCosts(Set.of(tripId), companyId).get(tripId);
        Map<String, BigDecimal> invoiced = repository.invoicedByCurrency(companyId, tripId);
        if (!invoiced.isEmpty()) {
            // The trip cost's currency when the carrier billed in it; otherwise the first, deterministically.
            String currency = cost != null && cost.currency() != null && invoiced.containsKey(cost.currency().trim())
                    ? cost.currency().trim()
                    : invoiced.keySet().iterator().next();
            // A trip credited below zero cost nothing to serve; it does not earn anything either.
            BigDecimal amount = invoiced.get(currency).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            return Optional.of(new ResolvedCost(AllocationCostSource.INVOICED, amount, currency));
        }
        if (cost == null || cost.currency() == null) {
            return Optional.empty();
        }
        if (cost.closed() && positive(cost.actualAmount())) {
            return Optional.of(new ResolvedCost(AllocationCostSource.ACTUAL,
                    cost.actualAmount().setScale(2, RoundingMode.HALF_UP), cost.currency().trim()));
        }
        if (positive(cost.expectedAmount())) {
            return Optional.of(new ResolvedCost(AllocationCostSource.ESTIMATED,
                    cost.expectedAmount().setScale(2, RoundingMode.HALF_UP), cost.currency().trim()));
        }
        return Optional.empty();
    }

    private boolean sameAs(UUID companyId, CostAllocationRun current, CostAllocationBasis basis, ResolvedCost cost,
            CostAllocator.Result result) {
        if (current.basis() != basis || current.costSource() != cost.source()
                || !current.currency().equals(cost.currency()) || current.costAmount().compareTo(cost.amount()) != 0
                || current.orderCount() != result.allocations().size()) {
            return false;
        }
        Map<UUID, BigDecimal> before = new HashMap<>();
        for (CostAllocationLine line : repository.findLines(companyId, current.id())) {
            before.put(line.assignmentId(), line.allocatedAmount());
        }
        for (CostAllocator.Allocation allocation : result.allocations()) {
            if (!before.containsKey(allocation.assignmentId())
                    || !sameAmount(before.get(allocation.assignmentId()), allocation.amount())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static LocalDate serviceDate(CompanyScope scope, TripAllocationSource trip) {
        if (trip.departedAt() != null) {
            return trip.departedAt().atZoneSameInstant(scope.zoneId()).toLocalDate();
        }
        return trip.planningDate() != null ? trip.planningDate() : LocalDate.now(scope.zoneId());
    }

    /** The customer reference, else the name: free text since V10, there is no customer master. */
    static String customerLabel(OrderCostReference order) {
        if (order.customerReference() != null && !order.customerReference().isBlank()) {
            return order.customerReference().trim();
        }
        if (order.customerName() != null && !order.customerName().isBlank()) {
            return order.customerName().trim();
        }
        return null;
    }

    private static String label(MasterReference reference) {
        return reference == null ? null : reference.code() + " - " + reference.name();
    }

    private static boolean positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }
}
