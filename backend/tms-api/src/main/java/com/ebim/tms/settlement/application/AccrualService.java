package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.AccrualBasis;
import com.ebim.tms.settlement.domain.AccrualEntryType;
import com.ebim.tms.settlement.domain.AccrualSourceType;
import com.ebim.tms.settlement.domain.AccrualStatus;
import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.DocumentType;
import com.ebim.tms.settlement.domain.CarrierInvoiceLine;
import com.ebim.tms.settlement.domain.FreightAccrual;
import com.ebim.tms.settlement.domain.FreightAccrualEntry;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import com.ebim.tms.settlement.infrastructure.AccrualReportQueries;
import com.ebim.tms.settlement.infrastructure.CarrierInvoiceRepository;
import com.ebim.tms.settlement.infrastructure.FreightAccrualEntryRepository;
import com.ebim.tms.settlement.infrastructure.FreightAccrualRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.AccruableTripLookupPort;
import com.ebim.tms.shared.reference.AccruableTripLookupPort.AccruableTrip;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.TripCostLookupPort;
import com.ebim.tms.shared.reference.TripCostLookupPort.TripCostSummary;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.AccrualTrigger;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freight accruals (migration V65, ADR-023 section 1): born from an executed trip, adjusted to the
 * closed actual cost, consumed by approved invoices, reversed by a person.
 *
 * <h2>Every total moves with its movement</h2>
 *
 * <p>{@link FreightAccrual}'s methods return the signed amount of the movement they imply, and every
 * caller here books that {@link FreightAccrualEntry} in the same transaction, under the accrual's row
 * lock. So the balance is always reproducible from the movements alone.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Birth: {@code uq_freight_accrual_trip_currency}. Movements: {@code uq_freight_accrual_entry_source}
 * - and before relying on it, each method checks the movement under the row lock, so a repeat in the
 * ordinary case is a no-op rather than an exception. The constraints are the backstop for two nodes.
 *
 * <h2>Never blocks the operation it follows</h2>
 *
 * <p>A dispatch never waits for this: the sweep ({@link AccrualSweep}) reads committed facts after
 * the fact. Approval does call {@link #consumeApprovedInvoice} in its own transaction, deliberately -
 * same module, and the consumption must not be lost - and it writes nothing when there is no accrual.
 */
@Service
public class AccrualService {

    /** Approved invoices that consume an accrual born after them (V46 statuses; phase 65 adds more). */
    static final Set<InvoiceStatus> CONSUMING_STATUSES = Set.of(InvoiceStatus.APPROVED, InvoiceStatus.EXPORTED);

    /** How many executed trips the "unaccrued" report lists before saying it stopped. */
    static final int UNACCRUED_LIMIT = 500;

    private static final int PAGE = 200;

    private final FreightAccrualRepository accrualRepository;
    private final FreightAccrualEntryRepository entryRepository;
    private final CarrierInvoiceRepository invoiceRepository;
    private final AccrualReportQueries reportQueries;
    private final AccruableTripLookupPort tripLookupPort;
    private final TripCostLookupPort tripCostLookupPort;
    private final CarrierLookupPort carrierLookupPort;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public AccrualService(FreightAccrualRepository accrualRepository, FreightAccrualEntryRepository entryRepository,
            CarrierInvoiceRepository invoiceRepository, AccrualReportQueries reportQueries,
            AccruableTripLookupPort tripLookupPort, TripCostLookupPort tripCostLookupPort,
            CarrierLookupPort carrierLookupPort, AuditRecorder auditRecorder, Clock clock) {
        this.accrualRepository = accrualRepository;
        this.entryRepository = entryRepository;
        this.invoiceRepository = invoiceRepository;
        this.reportQueries = reportQueries;
        this.tripLookupPort = tripLookupPort;
        this.tripCostLookupPort = tripCostLookupPort;
        this.carrierLookupPort = carrierLookupPort;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ birth

    /**
     * Accrues one executed trip, priced already. Called by the sweep inside a transaction of its own
     * per trip, so one trip that fails does not hold back the others.
     *
     * @param actorId the person who asked ("accrue now"), or null for the scheduled sweep
     * @return true if this call created it; false if it already existed
     */
    @Transactional
    public boolean accrue(CompanyScope scope, AccrualTrigger trigger, AccruableTrip trip,
            AccrualPricing.Priced priced, UUID actorId) {
        if (accrualRepository.findForUpdate(scope.companyId(), trip.tripId(), priced.currency()).isPresent()) {
            return false;
        }
        LocalDate serviceDate = trip.factAt(trigger).atZoneSameInstant(scope.zoneId()).toLocalDate();
        FreightAccrual accrual = accrualRepository.saveAndFlush(new FreightAccrual(scope.companyId(), trip.tripId(),
                trip.carrierId(), priced.currency(), priced.basis(), serviceDate, priced.amount()));
        AccrualSourceType source = trigger == AccrualTrigger.DELIVERED
                ? AccrualSourceType.TRIP_COMPLETED
                : AccrualSourceType.TRIP_DISPATCHED;
        entryRepository.save(new FreightAccrualEntry(accrual, AccrualEntryType.ACCRUE, accrual.accruedAmount(),
                serviceDate, source, trip.tripId().toString(), null, null, actorId));
        auditRecorder.record(scope, AuditAggregateType.FREIGHT_ACCRUAL, accrual.id(), AuditAction.ACCRUAL_CREATED,
                Map.of("tripId", trip.tripId().toString(),
                        "shipmentNumber", String.valueOf(trip.shipmentNumber()),
                        "currency", accrual.currency(),
                        "amount", accrual.accruedAmount().toPlainString(),
                        "basis", accrual.basis().name(),
                        "serviceDate", serviceDate.toString()));

        // An invoice approved before the accrual existed still consumes it: the order in which the
        // sweep and the approval happened must not change the balance.
        for (Object[] row : invoiceRepository.sumApprovedLinesForTrip(scope.companyId(), trip.tripId(),
                accrual.currency(), CONSUMING_STATUSES)) {
            consumeOne(scope, accrual, (UUID) row[0], (String) row[1], (BigDecimal) row[2], actorId);
        }
        accrualRepository.save(accrual);
        return true;
    }

    // ------------------------------------------------------------------ adjust

    /**
     * Moves an open accrual to its trip's closed actual cost. Called by the sweep on a claimed row
     * (held under {@code FOR UPDATE SKIP LOCKED}), inside the claiming transaction.
     *
     * @return true if a movement (or a basis change) was booked
     */
    public boolean adjustToClosedActual(CompanyScope scope, FreightAccrual accrual, TripCostSummary cost) {
        if (accrual.status() != AccrualStatus.OPEN || cost == null || !cost.closed()
                || cost.actualAmount() == null || cost.actualAmount().signum() < 0
                || !accrual.currency().equals(cost.currency())) {
            return false;
        }
        boolean differs = cost.actualAmount().compareTo(accrual.balance()) != 0;
        if (!differs && accrual.basis() == AccrualBasis.ACTUAL) {
            return false;
        }
        String key = cost.closedAt() == null ? "closed" : cost.closedAt().toInstant().toString();
        if (differs && entryRepository.existsByCompanyIdAndAccrualIdAndEntryTypeAndSourceTypeAndSourceKey(
                scope.companyId(), accrual.id(), AccrualEntryType.ADJUST, AccrualSourceType.TRIP_COST_ACTUAL, key)) {
            return false;
        }
        BigDecimal before = accrual.balance();
        BigDecimal delta = accrual.adjustTo(cost.actualAmount());
        if (delta.signum() != 0) {
            entryRepository.save(new FreightAccrualEntry(accrual, AccrualEntryType.ADJUST, delta,
                    movementPeriod(scope, accrual), AccrualSourceType.TRIP_COST_ACTUAL, key, null, null, null));
            auditRecorder.record(scope, AuditAggregateType.FREIGHT_ACCRUAL, accrual.id(), AuditAction.ACCRUAL_ADJUSTED,
                    Map.of("from", before.toPlainString(), "to", accrual.balance().toPlainString(),
                            "delta", delta.toPlainString(), "currency", accrual.currency()));
        }
        accrualRepository.save(accrual);
        return true;
    }

    // ------------------------------------------------------------------ consume

    /**
     * Consumes, for each trip the approved invoice bills, the accrual of that trip in the invoice's
     * currency. Runs inside {@code SettlementService.approve}'s transaction. The figure is the line
     * amount: on a document broken down (V66) the lines are its valor de venta, so the accrual is
     * consumed by the subtotal, net of IGV. Only a factura consumes.
     *
     * @return how many accruals were consumed
     */
    @Transactional
    public int consumeApprovedInvoice(CompanyScope scope, CarrierInvoice invoice, UUID actorId) {
        if (invoice.documentType() != DocumentType.INVOICE) {
            // A credit or debit note corrects the payable, and the ERP books it against expense; it
            // does not draw on, or give back to, the accrual the invoice consumed (phase 60).
            return 0;
        }
        // The lines are the valor de venta, net of IGV, once the document is broken down (V66): the
        // accrual is consumed by the subtotal, because IGV is fiscal credit and not expense.
        // Sorted by trip id so two approvals touching the same trips lock them in the same order.
        Map<UUID, BigDecimal> byTrip = new TreeMap<>();
        for (CarrierInvoiceLine line : invoice.lines()) {
            if (line.tripId() != null) {
                byTrip.merge(line.tripId(), line.lineAmount(), BigDecimal::add);
            }
        }
        int consumed = 0;
        for (Map.Entry<UUID, BigDecimal> trip : byTrip.entrySet()) {
            Optional<FreightAccrual> accrual =
                    accrualRepository.findForUpdate(scope.companyId(), trip.getKey(), invoice.currency());
            if (accrual.isPresent() && consumeOne(scope, accrual.get(), invoice.id(), invoice.invoiceNumber(),
                    trip.getValue(), actorId)) {
                accrualRepository.save(accrual.get());
                consumed++;
            }
        }
        return consumed;
    }

    private boolean consumeOne(CompanyScope scope, FreightAccrual accrual, UUID invoiceId, String invoiceNumber,
            BigDecimal invoiced, UUID actorId) {
        if (invoiced == null || invoiced.signum() <= 0 || accrual.status() == AccrualStatus.REVERSED) {
            // Nothing to draw (a credit-only trip, phase 60), or nothing left to draw on.
            return false;
        }
        String key = invoiceId.toString();
        if (entryRepository.existsByCompanyIdAndAccrualIdAndEntryTypeAndSourceTypeAndSourceKey(scope.companyId(),
                accrual.id(), AccrualEntryType.CONSUME, AccrualSourceType.INVOICE_APPROVED, key)) {
            return false;
        }
        BigDecimal signed = accrual.consume(invoiced);
        entryRepository.save(new FreightAccrualEntry(accrual, AccrualEntryType.CONSUME, signed,
                movementPeriod(scope, accrual), AccrualSourceType.INVOICE_APPROVED, key, invoiceId, null, actorId));
        auditRecorder.record(scope, AuditAggregateType.FREIGHT_ACCRUAL, accrual.id(), AuditAction.ACCRUAL_CONSUMED,
                Map.of("invoiceId", key, "invoiceNumber", String.valueOf(invoiceNumber),
                        "amount", invoiced.toPlainString(), "balance", accrual.balance().toPlainString(),
                        "status", accrual.status().name()));
        return true;
    }

    // ------------------------------------------------------------------ reverse

    /**
     * A person releases the balance: reverses an {@code OPEN} accrual (service not billable) or
     * closes the remainder of a {@code PARTIALLY_SETTLED} one. A dispatched trip cannot be cancelled
     * today, so there is no automatic reversal (ADR-023).
     */
    @Transactional
    public AccrualView reverse(CompanyScope scope, UUID accrualId, UUID actorId, String reason) {
        FreightAccrual accrual = accrualRepository.findByIdAndCompanyIdForUpdate(accrualId, scope.companyId())
                .orElseThrow(AccrualService::notFound);
        if (accrual.status() != AccrualStatus.OPEN && accrual.status() != AccrualStatus.PARTIALLY_SETTLED) {
            throw new ConflictException("This accrual is " + accrual.status()
                    + "; only an open or partially settled accrual has a balance to release.");
        }
        if (accrual.balance().signum() <= 0) {
            throw new ConflictException("This accrual has no remaining balance to release.");
        }
        AccrualStatus before = accrual.status();
        BigDecimal signed = accrual.reverseRemainder();
        entryRepository.save(new FreightAccrualEntry(accrual, AccrualEntryType.REVERSE, signed,
                movementPeriod(scope, accrual), AccrualSourceType.MANUAL, UUID.randomUUID().toString(), null,
                reason.trim(), actorId));
        accrualRepository.save(accrual);
        auditRecorder.record(scope, AuditAggregateType.FREIGHT_ACCRUAL, accrual.id(), AuditAction.ACCRUAL_REVERSED,
                Map.of("amount", signed.negate().toPlainString(), "currency", accrual.currency(),
                        "from", before.name(), "to", accrual.status().name(), "reason", reason.trim()));
        return get(scope, accrual.id());
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PageResponse<AccrualSummaryView> list(CompanyScope scope, YearMonth period, UUID carrierId,
            String currency, AccrualStatus status, PageQuery pageQuery) {
        UUID companyId = scope.companyId();
        Specification<FreightAccrual> spec = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
        if (period != null) {
            spec = spec.and((root, query, cb) -> cb.between(root.get("serviceDate"),
                    period.atDay(1), period.atEndOfMonth()));
        }
        if (carrierId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("carrierId"), carrierId));
        }
        if (currency != null && !currency.isBlank()) {
            String code = currency.trim().toUpperCase(java.util.Locale.ROOT);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("currency"), code));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        Page<FreightAccrual> page = accrualRepository.findAll(spec, PageRequest.of(pageQuery.pageNumber(),
                pageQuery.pageSize(), Sort.by(Sort.Order.desc("serviceDate"), Sort.Order.desc("createdAt"))));
        List<AccrualSummaryView> content = summaries(scope, page.getContent());
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AccrualView get(CompanyScope scope, UUID accrualId) {
        FreightAccrual accrual = accrualRepository.findByIdAndCompanyId(accrualId, scope.companyId())
                .orElseThrow(AccrualService::notFound);
        List<AccrualEntryView> entries = entryRepository
                .findByCompanyIdAndAccrualIdOrderByCreatedAtAscIdAsc(scope.companyId(), accrual.id()).stream()
                .map(AccrualEntryView::of)
                .toList();
        return new AccrualView(summaries(scope, List.of(accrual)).get(0), entries);
    }

    @Transactional(readOnly = true)
    public AccrualAgingView aging(CompanyScope scope, LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now(clock.withZone(scope.zoneId())) : asOf;
        OffsetDateTime cutoff = date.plusDays(1).atStartOfDay(scope.zoneId()).toOffsetDateTime();
        List<AccrualReportQueries.AgingRow> rows = reportQueries.aging(scope.companyId(), date, cutoff);
        Map<UUID, String> carriers = carrierNames(scope, rows.stream().map(AccrualReportQueries.AgingRow::carrierId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return new AccrualAgingView(date, rows.stream()
                .map(row -> new AccrualAgingView.Row(row.carrierId(), carriers.get(row.carrierId()), row.currency(),
                        row.accruals(), row.days0To30(), row.days31To60(), row.days61To90(), row.over90(),
                        row.total()))
                .toList());
    }

    /**
     * Executed trips in {@code [from, to]} (company calendar days) with no accrual, and why. Pages
     * through the trips the same way the sweep does, so the two cannot disagree on what is executed.
     */
    @Transactional(readOnly = true)
    public UnaccruedTripsView unaccrued(CompanyScope scope, AccrualTrigger trigger, LocalDate from, LocalDate to) {
        requireRange(from, to);
        List<UnaccruedTripsView.Row> rows = new ArrayList<>();
        Map<UUID, AccruableTrip> pending = new LinkedHashMap<>();
        Map<UUID, UnaccruedReason> reasons = new LinkedHashMap<>();
        boolean truncated = false;
        AccruableTrip after = null;
        OffsetDateTime start = from.atStartOfDay(scope.zoneId()).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(scope.zoneId()).toOffsetDateTime();
        while (!truncated) {
            List<AccruableTrip> page = tripLookupPort.findExecuted(scope.companyId(), trigger, start, end, after, PAGE);
            if (page.isEmpty()) {
                break;
            }
            after = page.get(page.size() - 1);
            Set<UUID> ids = page.stream().map(AccruableTrip::tripId).collect(Collectors.toSet());
            Set<UUID> accrued = new HashSet<>(accrualRepository.findAccruedTripIds(scope.companyId(), ids));
            Map<UUID, TripCostSummary> costs = tripCostLookupPort.findCosts(ids, scope.companyId());
            for (AccruableTrip trip : page) {
                if (accrued.contains(trip.tripId())) {
                    continue;
                }
                AccrualPricing pricing = AccrualPricing.of(trip, costs.get(trip.tripId()));
                UnaccruedReason reason = switch (pricing) {
                    case AccrualPricing.OwnFleet ignored -> null;
                    case AccrualPricing.Unpriced unpriced -> unpriced.reason();
                    case AccrualPricing.Priced ignored -> UnaccruedReason.PENDING_SWEEP;
                };
                if (reason == null) {
                    continue;
                }
                if (pending.size() >= UNACCRUED_LIMIT) {
                    truncated = true;
                    break;
                }
                pending.put(trip.tripId(), trip);
                reasons.put(trip.tripId(), reason);
            }
            if (page.size() < PAGE) {
                break;
            }
        }
        Map<UUID, String> carriers = carrierNames(scope, pending.values().stream().map(AccruableTrip::carrierId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        for (AccruableTrip trip : pending.values()) {
            rows.add(new UnaccruedTripsView.Row(trip.tripId(), trip.shipmentNumber(), trip.carrierId(),
                    carriers.get(trip.carrierId()), trip.factAt(trigger), reasons.get(trip.tripId()).name()));
        }
        return new UnaccruedTripsView(from, to, trigger.name(), rows, truncated);
    }

    // ------------------------------------------------------------------ internals

    /**
     * The accounting month of a movement other than the birth: the current month in the company's
     * calendar, never earlier than the month the service was rendered in.
     */
    LocalDate movementPeriod(CompanyScope scope, FreightAccrual accrual) {
        LocalDate today = LocalDate.now(clock.withZone(scope.zoneId()));
        LocalDate month = today.withDayOfMonth(1);
        LocalDate serviceMonth = accrual.serviceDate().withDayOfMonth(1);
        return month.isBefore(serviceMonth) ? serviceMonth : month;
    }

    static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidRequestException("from and to are required.");
        }
        if (to.isBefore(from)) {
            throw new InvalidRequestException("to must not be before from.");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw new InvalidRequestException("The range is at most one year.");
        }
    }

    private List<AccrualSummaryView> summaries(CompanyScope scope, List<FreightAccrual> accruals) {
        Set<UUID> tripIds = accruals.stream().map(FreightAccrual::tripId).collect(Collectors.toSet());
        Map<UUID, AccruableTrip> trips = tripLookupPort.findByIds(tripIds, scope.companyId());
        Map<UUID, String> carriers = carrierNames(scope, accruals.stream().map(FreightAccrual::carrierId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return accruals.stream()
                .map(accrual -> AccrualSummaryView.of(accrual,
                        Optional.ofNullable(trips.get(accrual.tripId())).map(AccruableTrip::shipmentNumber).orElse(null),
                        carriers.get(accrual.carrierId())))
                .toList();
    }

    private Map<UUID, String> carrierNames(CompanyScope scope, Set<UUID> carrierIds) {
        if (carrierIds.isEmpty()) {
            return Map.of();
        }
        return carrierLookupPort.findAllInCompany(carrierIds, scope.companyId()).values().stream()
                .collect(Collectors.toMap(MasterReference::id, MasterReference::name, (a, b) -> a));
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Accrual not found.");
    }
}
