package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CostAllocator;
import com.ebim.tms.settlement.domain.EmissionCalculator;
import com.ebim.tms.settlement.infrastructure.CostToServeQueries.GroupBy;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.io.DelimitedTextWriter;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.EmissionFactorLookupPort;
import com.ebim.tms.shared.reference.EmissionFactorLookupPort.EmissionFactor;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderReferencePort;
import com.ebim.tms.shared.reference.OrderReferencePort.OrderCostReference;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.TripEmissionSourcePort;
import com.ebim.tms.shared.reference.TripEmissionSourcePort.TripEmissionSource;
import com.ebim.tms.shared.reference.TripSettlementLookupPort.TripLoad;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CostAllocationBasis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The carbon footprint of a trip and of a period (migration V73): "carbon to serve", beside cost to
 * serve (ADR-026) and built from the same parts.
 *
 * <ul>
 *   <li>the distance is the trip's own (V38), measured by planning with its routing provenance;</li>
 *   <li>the factor is the vehicle type's (V73), in kg CO2e per km;</li>
 *   <li>the split between orders is {@link CostAllocator} on the company's cost-allocation basis -
 *       the machinery that splits the trip's cost, so an order's share of the carbon is its share of
 *       the cost.</li>
 * </ul>
 *
 * <p>Computed on read and stored nowhere: a factor corrected tomorrow corrects every report, which is
 * what a sustainability figure should do, unlike a cost already billed.
 */
@Service
public class TripEmissionService {

    /** The most trips one report measures: each one is a routing walk over its stops. */
    static final int MAX_TRIPS_PER_REPORT = 2000;

    /** A quarter: a footprint report re-measures every trip of the range. */
    static final int MAX_RANGE_DAYS = 93;

    private final TripEmissionSourcePort sourcePort;
    private final EmissionFactorLookupPort factorPort;
    private final CostAllocationService allocationService;
    private final OrderReferencePort orderReferencePort;
    private final DestinationLookupPort destinationLookupPort;
    private final RouteTemplateLookupPort routeLookupPort;

    public TripEmissionService(TripEmissionSourcePort sourcePort, EmissionFactorLookupPort factorPort,
            CostAllocationService allocationService, OrderReferencePort orderReferencePort,
            DestinationLookupPort destinationLookupPort, RouteTemplateLookupPort routeLookupPort) {
        this.sourcePort = sourcePort;
        this.factorPort = factorPort;
        this.allocationService = allocationService;
        this.orderReferencePort = orderReferencePort;
        this.destinationLookupPort = destinationLookupPort;
        this.routeLookupPort = routeLookupPort;
    }

    @Transactional(readOnly = true)
    public TripEmissionView trip(CompanyScope scope, UUID tripId) {
        TripEmissionSource source = sourcePort.findSource(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        CostAllocationBasis basis = allocationService.basisOf(scope.companyId());
        Map<UUID, EmissionFactor> factors = factorsOf(scope, List.of(source));
        Map<UUID, OrderCostReference> orders = ordersOf(scope, List.of(source));
        Map<UUID, MasterReference> destinations = destinationsOf(scope, orders);
        return view(source, factors, orders, destinations, basis);
    }

    @Transactional(readOnly = true)
    public EmissionReportView report(CompanyScope scope, LocalDate from, LocalDate to, GroupBy groupBy) {
        requireRange(from, to);
        GroupBy by = groupBy == null ? GroupBy.CUSTOMER : groupBy;
        CostAllocationBasis basis = allocationService.basisOf(scope.companyId());
        List<TripEmissionSource> sources =
                sourcePort.findInPeriod(scope.companyId(), from, to, MAX_TRIPS_PER_REPORT + 1);
        boolean truncated = sources.size() > MAX_TRIPS_PER_REPORT;
        if (truncated) {
            sources = sources.subList(0, MAX_TRIPS_PER_REPORT);
        }
        Map<UUID, EmissionFactor> factors = factorsOf(scope, sources);
        Map<UUID, OrderCostReference> orders = ordersOf(scope, sources);
        Map<UUID, MasterReference> destinations = destinationsOf(scope, orders);
        Map<UUID, String> routes = routesOf(scope, sources);

        long calculated = 0;
        long estimated = 0;
        long noFactor = 0;
        long noDistance = 0;
        long noVehicle = 0;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        BigDecimal distance = BigDecimal.ZERO;
        BigDecimal unallocated = BigDecimal.ZERO.setScale(2);
        Map<String, GroupAccumulator> groups = new LinkedHashMap<>();
        for (TripEmissionSource source : sources) {
            TripEmissionView trip = view(source, factors, orders, destinations, basis);
            switch (trip.status()) {
                case CALCULATED -> calculated++;
                case NO_FACTOR -> noFactor++;
                case NO_DISTANCE -> noDistance++;
                case NO_VEHICLE -> noVehicle++;
            }
            if (trip.status() != EmissionCalculator.Status.CALCULATED) {
                continue;
            }
            if (trip.estimated()) {
                estimated++;
            }
            total = total.add(trip.kgCo2e());
            distance = distance.add(trip.distanceKm());
            BigDecimal reached = BigDecimal.ZERO;
            Map<UUID, BigDecimal> weights = new HashMap<>();
            source.loads().forEach(load -> weights.put(load.assignmentId(), load.weightKg()));
            for (TripEmissionView.OrderEmission order : trip.orders()) {
                if (order.kgCo2e() == null) {
                    continue;
                }
                reached = reached.add(order.kgCo2e());
                String key;
                String label;
                switch (by) {
                    case DESTINATION -> {
                        key = order.destinationId() == null ? null : order.destinationId().toString();
                        label = order.destinationLabel();
                    }
                    case ROUTE -> {
                        key = source.routeId() == null ? null : source.routeId().toString();
                        label = routes.get(source.routeId());
                    }
                    default -> {
                        key = order.customerLabel();
                        label = order.customerLabel();
                    }
                }
                groups.computeIfAbsent(String.valueOf(key), unused -> new GroupAccumulator(key, label))
                        .add(source.tripId(), order.kgCo2e(), weights.get(order.assignmentId()));
            }
            unallocated = unallocated.add(trip.kgCo2e().subtract(reached));
        }

        List<EmissionReportView.Group> rows = groups.values().stream()
                .map(GroupAccumulator::toGroup)
                .sorted((a, b) -> b.kgCo2e().compareTo(a.kgCo2e()))
                .toList();
        return new EmissionReportView(from, to, by.name(), basis.name(),
                new EmissionReportView.Totals(sources.size(), calculated, estimated, noFactor, noDistance, noVehicle,
                        total, distance.setScale(2, RoundingMode.HALF_UP), unallocated),
                rows, truncated);
    }

    /** The groups as CSV: one row per group. */
    public static byte[] csv(EmissionReportView report) {
        List<String> header = List.of("group_by", "key", "label", "trips", "orders", "kg_co2e", "weight_kg",
                "kg_co2e_per_tonne", "basis");
        List<List<String>> rows = new ArrayList<>();
        for (EmissionReportView.Group group : report.groups()) {
            rows.add(List.of(report.groupBy(), text(group.key()), text(group.label()), String.valueOf(group.trips()),
                    String.valueOf(group.orders()), text(group.kgCo2e()), text(group.weightKg()),
                    text(group.kgCo2ePerTonne()), report.basis()));
        }
        return DelimitedTextWriter.write(header, rows);
    }

    // ------------------------------------------------------------------ one trip

    private TripEmissionView view(TripEmissionSource source, Map<UUID, EmissionFactor> factors,
            Map<UUID, OrderCostReference> orders, Map<UUID, MasterReference> destinations, CostAllocationBasis basis) {
        EmissionFactor factor = source.vehicleTypeId() == null ? null : factors.get(source.vehicleTypeId());
        EmissionCalculator.Result result = EmissionCalculator.compute(source.vehicleId() != null,
                factor == null ? null : factor.kgCo2ePerKm(), source.distanceKm());

        BigDecimal weight = source.loads().stream().map(TripLoad::weightKg).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<TripEmissionView.OrderEmission> rows = new ArrayList<>();
        int unallocatedCount = 0;
        com.ebim.tms.settlement.domain.UnallocatedReason reason = null;
        Map<UUID, CostAllocator.Allocation> byAssignment = new HashMap<>();
        if (result.calculated()) {
            List<CostAllocator.Load> loads = source.loads().stream()
                    .map(load -> {
                        OrderCostReference order = orders.get(load.orderId());
                        return new CostAllocator.Load(load.assignmentId(),
                                order == null ? null : order.destinationId(), load.weightKg(), load.volumeM3(),
                                load.pallets(), load.floorAreaM2());
                    })
                    .toList();
            CostAllocator.Result split = CostAllocator.allocate(result.kgCo2e(), basis, loads);
            split.allocations().forEach(allocation -> byAssignment.put(allocation.assignmentId(), allocation));
            unallocatedCount = split.unallocatedCount();
            reason = split.reason();
        }
        for (TripLoad load : source.loads()) {
            OrderCostReference order = orders.get(load.orderId());
            UUID destinationId = order == null ? null : order.destinationId();
            MasterReference destination = destinationId == null ? null : destinations.get(destinationId);
            CostAllocator.Allocation allocation = byAssignment.get(load.assignmentId());
            rows.add(new TripEmissionView.OrderEmission(load.assignmentId(), load.orderId(),
                    order == null ? load.orderId().toString() : order.orderNumber(),
                    order == null ? null : CostAllocationService.customerLabel(order), destinationId,
                    destination == null ? null : destination.code() + " - " + destination.name(),
                    allocation == null ? null : allocation.basisQuantity(),
                    allocation == null ? null : allocation.share(),
                    allocation == null ? null : allocation.amount()));
        }
        return new TripEmissionView(source.tripId(), source.shipmentNumber(), result.status(), result.kgCo2e(),
                source.distanceKm() == null ? null : source.distanceKm().setScale(2, RoundingMode.HALF_UP),
                source.estimated(), source.unmeasurableLegs(), source.provider(), source.vehiclePlate(),
                source.vehicleTypeId(), factor == null ? null : factor.vehicleTypeCode(),
                factor == null ? null : factor.vehicleTypeName(), factor == null ? null : factor.fuelType(),
                factor == null ? null : factor.kgCo2ePerKm(), basis.name(), weight,
                EmissionCalculator.gramsPerTonneKm(result.kgCo2e(), source.distanceKm(), weight),
                unallocatedCount, reason, List.copyOf(rows));
    }

    // ------------------------------------------------------------------ batched lookups

    private Map<UUID, EmissionFactor> factorsOf(CompanyScope scope, List<TripEmissionSource> sources) {
        Set<UUID> types = sources.stream().map(TripEmissionSource::vehicleTypeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return types.isEmpty() ? Map.of() : factorPort.factorsOf(types, scope.companyId());
    }

    private Map<UUID, OrderCostReference> ordersOf(CompanyScope scope, List<TripEmissionSource> sources) {
        Set<UUID> orderIds = sources.stream().flatMap(source -> source.loads().stream()).map(TripLoad::orderId)
                .collect(Collectors.toSet());
        return orderIds.isEmpty() ? Map.of() : orderReferencePort.costReferencesOf(orderIds, scope.companyId());
    }

    private Map<UUID, MasterReference> destinationsOf(CompanyScope scope, Map<UUID, OrderCostReference> orders) {
        Set<UUID> ids = orders.values().stream().map(OrderCostReference::destinationId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : destinationLookupPort.findAllInCompany(ids, scope.companyId());
    }

    private Map<UUID, String> routesOf(CompanyScope scope, List<TripEmissionSource> sources) {
        Set<UUID> ids = sources.stream().map(TripEmissionSource::routeId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> labels = new HashMap<>();
        for (Map.Entry<UUID, RouteTemplate> entry : routeLookupPort.findAllInCompany(ids, scope.companyId())
                .entrySet()) {
            labels.put(entry.getKey(), entry.getValue().code() + " - " + entry.getValue().name());
        }
        return labels;
    }

    static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidRequestException("from and to are required.");
        }
        if (to.isBefore(from)) {
            throw new InvalidRequestException("to must not be before from.");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new InvalidRequestException("The range is at most " + MAX_RANGE_DAYS + " days.");
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
    }

    /** One group of the report, counting each trip once however many of its orders fall in it. */
    private static final class GroupAccumulator {
        private final String key;
        private final String label;
        private final Set<UUID> trips = new HashSet<>();
        private long orders;
        private BigDecimal kgCo2e = BigDecimal.ZERO.setScale(2);
        private BigDecimal weightKg = BigDecimal.ZERO;

        GroupAccumulator(String key, String label) {
            this.key = key;
            this.label = label;
        }

        void add(UUID tripId, BigDecimal kg, BigDecimal weight) {
            trips.add(tripId);
            orders++;
            kgCo2e = kgCo2e.add(kg);
            if (weight != null) {
                weightKg = weightKg.add(weight);
            }
        }

        EmissionReportView.Group toGroup() {
            BigDecimal perTonne = weightKg.signum() <= 0 ? null
                    : kgCo2e.multiply(BigDecimal.valueOf(1000)).divide(weightKg, 4, RoundingMode.HALF_UP);
            return new EmissionReportView.Group(key, label, trips.size(), orders, kgCo2e,
                    weightKg.setScale(3, RoundingMode.HALF_UP), perTonne);
        }
    }
}
