package com.ebim.tms.planning.application;

import com.ebim.tms.planning.application.CarrierScorecardView.CarrierRow;
import com.ebim.tms.planning.application.CarrierScorecardView.CostFigures;
import com.ebim.tms.planning.application.CarrierScorecardView.DocumentFigures;
import com.ebim.tms.planning.application.CarrierScorecardView.InvoiceFigures;
import com.ebim.tms.planning.application.CarrierScorecardView.TenderFigures;
import com.ebim.tms.planning.domain.KpiRate;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.DeliveryTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.DocumentTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.ExceptionTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.StopTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.TenderTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.TripTotals;
import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries.TripWeight;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.io.DelimitedTextWriter;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.SettlementKpiPort;
import com.ebim.tms.shared.reference.SettlementKpiPort.CarrierSettlementTotals;
import com.ebim.tms.shared.reference.TripCostAnalyticsPort;
import com.ebim.tms.shared.reference.TripCostFigure;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The carrier scorecard ({@code docs/domain/CARRIER_SCORECARD_V1.md}): "which carrier delivers for
 * us", per carrier and for our own fleet, over a range of operating days.
 *
 * <p><b>Read-only and ruleless</b>, like {@link KpiService}, which it sits beside for the same reason
 * (trips, stops, deliveries, exceptions, documents and tenders are planning's): every count is a
 * grouped aggregate from {@link CarrierScorecardQueries}, every division goes through
 * {@link KpiRate} so an empty denominator is null and never 0%, and cost and invoices come through
 * {@link TripCostAnalyticsPort} and {@link SettlementKpiPort} - never by reading another module's
 * tables.
 *
 * <p><b>Query budget</b>: six grouped statements, plus the carrier names in one batched lookup, plus
 * - only for a caller entitled to them - one invoice aggregate and one cost read with one weight
 * aggregate. None grows with the number of carriers; the range cap ({@link KpiRange#MAX_DAYS}) bounds
 * them all.
 */
@Service
public class CarrierScorecardService {

    /** How many trips the drill-down lists before it says it stopped. */
    public static final int TRIP_LIMIT = 500;

    private final CarrierScorecardQueries queries;
    private final CarrierLookupPort carrierLookupPort;
    private final SettlementKpiPort settlementKpiPort;
    private final TripCostAnalyticsPort tripCostAnalyticsPort;
    private final Clock clock;

    public CarrierScorecardService(CarrierScorecardQueries queries, CarrierLookupPort carrierLookupPort,
            SettlementKpiPort settlementKpiPort, TripCostAnalyticsPort tripCostAnalyticsPort, Clock clock) {
        this.queries = queries;
        this.carrierLookupPort = carrierLookupPort;
        this.settlementKpiPort = settlementKpiPort;
        this.tripCostAnalyticsPort = tripCostAnalyticsPort;
        this.clock = clock;
    }

    /** The whole scorecard, in one read-only transaction so every block is one snapshot. */
    @Transactional(readOnly = true)
    public CarrierScorecardView report(CompanyScope scope, KpiFilter filter) {
        KpiRange range = KpiRange.of(scope, filter);
        UUID companyId = scope.companyId();
        OffsetDateTime now = OffsetDateTime.now(clock);

        boolean tendersVisible = scope.has(Permission.PLANNING_TENDER_READ);
        boolean documentsVisible = scope.has(Permission.PLANNING_DOCUMENT_DELIVERY_READ);
        boolean invoicesVisible = scope.has(Permission.SETTLEMENT_INVOICE_READ);
        boolean costVisible = scope.has(Permission.RATES_TRIP_COST_READ);

        Map<String, TripTotals> trips = byKey(queries.tripTotals(companyId, range.from(), range.to()),
                TripTotals::carrierKey);
        Map<String, StopTotals> stops = byKey(queries.stopTotals(companyId, range.from(), range.to(),
                scope.zoneId().getId()), StopTotals::carrierKey);
        Map<String, DeliveryTotals> deliveries = byKey(queries.deliveryTotals(companyId, range.from(), range.to()),
                DeliveryTotals::carrierKey);
        Map<String, ExceptionTotals> exceptions = byKey(
                queries.exceptionTotals(companyId, range.from(), range.to()), ExceptionTotals::carrierKey);
        Map<String, DocumentTotals> documents = documentsVisible
                ? byKey(queries.documentTotals(companyId, range.from(), range.to()), DocumentTotals::carrierKey)
                : Map.of();
        Map<String, TenderTotals> tenders = tendersVisible
                ? byKey(queries.tenderTotals(companyId, range.from(), range.to(), now), TenderTotals::carrierKey)
                : Map.of();
        Map<String, List<InvoiceFigures>> invoices = invoicesVisible ? invoices(companyId, range) : Map.of();
        Map<String, List<CostFigures>> cost = costVisible ? cost(companyId, range) : Map.of();

        // Every carrier that has anything to show: trips it ran, offers it was made, invoices it
        // sent. A carrier that refused every offer has no trip and is precisely the one to see.
        Set<String> keys = new HashSet<>();
        keys.addAll(trips.keySet());
        keys.addAll(tenders.keySet());
        keys.addAll(invoices.keySet());
        keys.remove(null);

        Map<UUID, MasterReference> names = names(companyId, keys);
        List<CarrierRow> rows = new ArrayList<>();
        for (String key : keys) {
            rows.add(row(key, names, trips.get(key), stops.get(key), deliveries.get(key), exceptions.get(key),
                    documentsVisible, documents.get(key), tendersVisible, tenders.get(key),
                    invoicesVisible ? invoices.getOrDefault(key, List.of()) : null,
                    costVisible ? cost.getOrDefault(key, List.of()) : null));
        }
        rows.sort(Comparator.comparingLong(CarrierRow::trips).reversed()
                .thenComparing(row -> row.carrierName() == null ? "" : row.carrierName(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CarrierRow::carrierKey));

        TripTotals unattributed = trips.get(null);
        return new CarrierScorecardView(range.from(), range.to(), range.days(), now,
                tendersVisible, documentsVisible, invoicesVisible, costVisible,
                unattributed == null ? 0 : unattributed.trips(), rows);
    }

    /** The drill-down: one carrier's trips in the range, newest first, capped and saying so. */
    @Transactional(readOnly = true)
    public CarrierScorecardTripsView trips(CompanyScope scope, KpiFilter filter, String carrier) {
        KpiRange range = KpiRange.of(scope, filter);
        String key = carrierKey(carrier);
        UUID carrierId = CarrierScorecardQueries.OWN_FLEET.equals(key) ? null : UUID.fromString(key);
        MasterReference master = carrierId == null ? null : names(scope.companyId(), Set.of(key)).get(carrierId);
        String name = master == null ? null : master.name();
        long total = queries.countTrips(scope.companyId(), range.from(), range.to(), key);
        List<CarrierScorecardQueries.TripRow> rows = queries.trips(scope.companyId(), range.from(), range.to(), key,
                scope.zoneId().getId(), TRIP_LIMIT);
        return new CarrierScorecardTripsView(key, carrierId, carrierId == null, name, range.from(), range.to(),
                total, total > rows.size(), rows);
    }

    /** The scorecard as CSV: the same figures as the screen, one carrier per row - see {@link CarrierScorecardCsv}. */
    @Transactional(readOnly = true)
    public KpiExportFile export(CompanyScope scope, KpiFilter filter) {
        CarrierScorecardView view = report(scope, filter);
        CarrierScorecardCsv csv = CarrierScorecardCsv.of(view);
        return new KpiExportFile("tms-carrier-scorecard-" + view.from() + "-to-" + view.to() + ".csv",
                DelimitedTextWriter.write(csv.header(), csv.rows()));
    }

    /**
     * Validates the drill-down's carrier: a carrier id or {@code OWN_FLEET}, nothing else. A malformed
     * value is a 400 and not an empty list, which would read as "this carrier ran nothing".
     */
    static String carrierKey(String carrier) {
        if (carrier == null || carrier.isBlank()) {
            throw new InvalidRequestException("Name the carrier: its id, or " + CarrierScorecardQueries.OWN_FLEET
                    + " for the company's own fleet.");
        }
        String trimmed = carrier.trim();
        if (CarrierScorecardQueries.OWN_FLEET.equalsIgnoreCase(trimmed)) {
            return CarrierScorecardQueries.OWN_FLEET;
        }
        try {
            return UUID.fromString(trimmed).toString();
        } catch (IllegalArgumentException malformed) {
            throw new InvalidRequestException("'" + trimmed + "' is neither a carrier id nor "
                    + CarrierScorecardQueries.OWN_FLEET + ".");
        }
    }

    // --- one row ----------------------------------------------------------------------------

    static CarrierRow row(String key, Map<UUID, MasterReference> names, TripTotals trips, StopTotals stops,
            DeliveryTotals deliveries, ExceptionTotals exceptions, boolean documentsVisible, DocumentTotals documents,
            boolean tendersVisible, TenderTotals tenders, List<InvoiceFigures> invoices, List<CostFigures> cost) {
        boolean ownFleet = CarrierScorecardQueries.OWN_FLEET.equals(key);
        UUID carrierId = ownFleet ? null : UUID.fromString(key);
        MasterReference carrier = carrierId == null ? null : names.get(carrierId);

        long tripCount = trips == null ? 0 : trips.trips();
        long measured = trips == null ? 0 : trips.departuresMeasured();
        long late = trips == null ? 0 : trips.departuresLate();
        long windowsMeasured = stops == null ? 0 : stops.windowsMeasured();
        long windowsMissed = stops == null ? 0 : stops.windowsMissed();
        long recorded = deliveries == null ? 0 : deliveries.recorded();
        long delivered = deliveries == null ? 0 : deliveries.delivered();
        long exceptionCount = exceptions == null ? 0 : exceptions.exceptions();

        return new CarrierRow(key, carrierId, ownFleet,
                carrier == null ? null : carrier.code(), carrier == null ? null : carrier.name(),
                tripCount, trips == null ? 0 : trips.cancelled(), trips == null ? 0 : trips.executed(),
                trips == null ? 0 : trips.completed(),
                measured, late, KpiRate.percentComplement(late, measured),
                stops == null ? 0 : stops.stops(), windowsMeasured, windowsMissed,
                KpiRate.percentComplement(windowsMissed, windowsMeasured),
                recorded, delivered, deliveries == null ? 0 : deliveries.shortfall(),
                deliveries == null ? 0 : deliveries.notAttempted(), KpiRate.percent(delivered, recorded),
                exceptionCount, exceptions == null ? 0 : exceptions.open(), KpiRate.per100(exceptionCount, tripCount),
                tendersVisible ? tenderFigures(tenders) : null,
                documentsVisible ? documentFigures(documents) : null,
                invoices, cost);
    }

    static TenderFigures tenderFigures(TenderTotals tenders) {
        if (tenders == null) {
            return new TenderFigures(0, 0, 0, 0, 0, 0, null, null);
        }
        long decided = tenders.accepted() + tenders.rejected() + tenders.expired();
        return new TenderFigures(tenders.offered(), tenders.accepted(), tenders.rejected(), tenders.expired(),
                tenders.awaiting(), tenders.timed(), KpiRate.percent(tenders.accepted(), decided),
                tenders.timed() == 0 ? null : tenders.averageResponseMinutes());
    }

    static DocumentFigures documentFigures(DocumentTotals documents) {
        if (documents == null) {
            return new DocumentFigures(0, 0, 0, null, null);
        }
        return new DocumentFigures(documents.documents(), documents.delivered(), documents.returned(),
                KpiRate.percent(documents.delivered(), documents.documents()),
                KpiRate.percent(documents.returned(), documents.delivered()));
    }

    // --- invoices and cost ------------------------------------------------------------------

    private Map<String, List<InvoiceFigures>> invoices(UUID companyId, KpiRange range) {
        Map<String, List<InvoiceFigures>> byCarrier = new HashMap<>();
        for (CarrierSettlementTotals row : settlementKpiPort.totals(companyId, range.from(), range.to()).byCarrier()) {
            if (row.carrierId() == null) {
                continue;
            }
            byCarrier.computeIfAbsent(row.carrierId().toString(), key -> new ArrayList<>())
                    .add(new InvoiceFigures(row.currency(), row.invoices(), row.withDiscrepancy(),
                            KpiRate.percent(row.withDiscrepancy(), row.invoices()), row.amountObserved()));
        }
        byCarrier.values().forEach(list -> list.sort(Comparator.comparing(InvoiceFigures::currency)));
        return byCarrier;
    }

    private Map<String, List<CostFigures>> cost(UUID companyId, KpiRange range) {
        Map<UUID, TripWeight> weights = queries.tripWeights(companyId, range.from(), range.to()).stream()
                .collect(Collectors.toMap(TripWeight::tripId, Function.identity()));
        return costByCarrier(weights, tripCostAnalyticsPort.figuresForRange(companyId, range.from(), range.to()));
    }

    /**
     * Sums the trips' costs per carrier and currency. A cost row whose trip is not one of the
     * attributed, not-cancelled trips of the range is left out - it is either a cancelled shipment or
     * nobody's - and a trip with no weight counts for the cost per trip and not for the cost per kg,
     * whose numerator and denominator are always the same set of trips.
     */
    static Map<String, List<CostFigures>> costByCarrier(Map<UUID, TripWeight> weights, List<TripCostFigure> figures) {
        Map<String, Map<String, CostAccumulator>> byCarrier = new HashMap<>();
        for (TripCostFigure figure : figures) {
            TripWeight trip = weights.get(figure.tripId());
            BigDecimal amount = figure.knownAmount();
            if (trip == null || amount == null) {
                continue;
            }
            CostAccumulator accumulator = byCarrier.computeIfAbsent(trip.carrierKey(), key -> new TreeMap<>())
                    .computeIfAbsent(figure.currency().trim(), currency -> new CostAccumulator());
            accumulator.trips++;
            if (figure.actualAmount() != null) {
                accumulator.withActual++;
            }
            accumulator.amount = accumulator.amount.add(amount);
            if (trip.weightKg() != null && trip.weightKg().signum() > 0) {
                accumulator.weighedAmount = accumulator.weighedAmount.add(amount);
                accumulator.weightKg = accumulator.weightKg.add(trip.weightKg());
            }
        }
        Map<String, List<CostFigures>> result = new LinkedHashMap<>();
        byCarrier.forEach((key, currencies) -> result.put(key, currencies.entrySet().stream()
                .map(entry -> entry.getValue().toFigures(entry.getKey()))
                .toList()));
        return result;
    }

    private static final class CostAccumulator {
        private long trips;
        private long withActual;
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal weighedAmount = BigDecimal.ZERO;
        private BigDecimal weightKg = BigDecimal.ZERO;

        private CostFigures toFigures(String currency) {
            return new CostFigures(currency, trips, withActual, amount.setScale(2, RoundingMode.HALF_UP),
                    trips == 0 ? null : amount.divide(BigDecimal.valueOf(trips), 2, RoundingMode.HALF_UP),
                    weightKg,
                    weightKg.signum() == 0 ? null : weighedAmount.divide(weightKg, 4, RoundingMode.HALF_UP));
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private Map<UUID, MasterReference> names(UUID companyId, Set<String> keys) {
        Set<UUID> ids = keys.stream()
                .filter(Objects::nonNull)
                .filter(key -> !CarrierScorecardQueries.OWN_FLEET.equals(key))
                .map(UUID::fromString)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : carrierLookupPort.findAllInCompany(ids, companyId);
    }

    /** Indexes one grouped result by its carrier key; the null key (unattributed) is kept. */
    private static <T> Map<String, T> byKey(List<T> rows, Function<T, String> key) {
        Map<String, T> map = new HashMap<>();
        rows.forEach(row -> map.put(key.apply(row), row));
        return map;
    }
}
