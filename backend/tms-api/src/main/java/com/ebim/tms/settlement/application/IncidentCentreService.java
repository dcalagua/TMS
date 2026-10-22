package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.application.FreightClaimViews.AgeingBuckets;
import com.ebim.tms.settlement.domain.ClaimStatus;
import com.ebim.tms.settlement.domain.FreightClaim;
import com.ebim.tms.settlement.infrastructure.FreightClaimRepository;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.TripIncidentLookupPort;
import com.ebim.tms.shared.reference.TripIncidentLookupPort.ClaimableTrip;
import com.ebim.tms.shared.reference.TripIncidentLookupPort.ExceptionIncident;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The incident centre (V71): the open trip exceptions and the freight claims of a company on one
 * list, with the same filters - status, carrier, type, period - and how old each one is.
 *
 * <p><b>It reads two modules and owns neither row.</b> Exceptions are planning's, reached through
 * {@link TripIncidentLookupPort}; resolving one stays planning's endpoint. Claims are this module's.
 * Nothing here writes.
 *
 * <p><b>A merged list is capped, not paged.</b> Paging a union of two sources means paging each of
 * them to the depth of the page, which is the cost of an unbounded scan by another name. The list
 * takes the newest {@code limit} of each source and says when either had more ({@code truncated}),
 * so a screen can ask for a narrower filter rather than show a partial list as if it were whole.
 */
@Service
public class IncidentCentreService {

    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 500;

    private static final Set<ClaimStatus> ACTIVE = EnumSet.of(ClaimStatus.OPEN, ClaimStatus.UNDER_REVIEW,
            ClaimStatus.ACCEPTED, ClaimStatus.PARTIALLY_ACCEPTED, ClaimStatus.REJECTED);
    /**
     * The {@code TripExceptionType} names (V27). Spelled here because settlement does not see
     * planning's enum; {@code IncidentCentreServiceTest} holds the two lists together.
     */
    static final Set<String> EXCEPTION_TYPES = Set.of("TRAFFIC_DELAY", "VEHICLE_BREAKDOWN", "CUSTOMER_CLOSED",
            "DELIVERY_REJECTED", "ADDRESS_NOT_FOUND", "DELIVERY_FAILED", "OTHER");

    private static final Set<ClaimStatus> FILED = EnumSet.of(ClaimStatus.CLOSED, ClaimStatus.CANCELLED);

    private final TripIncidentLookupPort incidents;
    private final FreightClaimRepository claimRepository;
    private final FreightClaimService claimService;
    private final Clock clock;

    public IncidentCentreService(TripIncidentLookupPort incidents, FreightClaimRepository claimRepository,
            FreightClaimService claimService, Clock clock) {
        this.incidents = incidents;
        this.claimRepository = claimRepository;
        this.claimService = claimService;
        this.clock = clock;
    }

    /** Which incidents: still being worked, already dealt with, or both. */
    public enum StatusGroup { OPEN, CLOSED, ALL }

    /** Exceptions, claims, or both. */
    public enum IncidentKind { EXCEPTION, CLAIM }

    /**
     * @param type a {@code TripExceptionType} or a {@code ClaimType} name; it narrows the kind it names
     */
    public record IncidentFilter(StatusGroup status, IncidentKind kind, UUID carrierId, String type, LocalDate from,
            LocalDate to, Integer limit) {
    }

    /**
     * One row: an exception or a claim.
     *
     * @param reference the claim number, or for an exception its shipment number
     * @param open      still being worked: an OPEN exception, a claim not closed or cancelled
     * @param ageDays   to today while open, to its resolution or close once dealt with
     * @param claims    for an exception, the claims opened from it - so the same problem is not claimed twice
     */
    public record IncidentRow(IncidentKind kind, UUID id, String reference, String type, String status, boolean open,
            UUID tripId, String shipmentNumber, UUID carrierId, String carrierName, OffsetDateTime occurredAt,
            OffsetDateTime closedAt, long ageDays, BigDecimal amount, String currency, String summary,
            List<String> claims) {
    }

    public record IncidentTotals(long openExceptions, long activeClaims, AgeingBuckets ageing) {
    }

    public record IncidentCentreView(List<IncidentRow> rows, IncidentTotals totals, boolean truncated, int limit) {
    }

    @Transactional(readOnly = true)
    public IncidentCentreView list(CompanyScope scope, IncidentFilter filter) {
        int limit = filter.limit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(filter.limit(), MAX_LIMIT));
        StatusGroup group = filter.status() == null ? StatusGroup.OPEN : filter.status();
        OffsetDateTime from = filter.from() == null ? null
                : filter.from().atStartOfDay(scope.zoneId()).toOffsetDateTime();
        OffsetDateTime to = filter.to() == null ? null
                : filter.to().plusDays(1).atStartOfDay(scope.zoneId()).toOffsetDateTime();
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidRequestException("The period ends before it starts.");
        }
        String type = filter.type() == null || filter.type().isBlank() ? null : filter.type().trim();
        boolean typeIsClaim = type != null && isClaimType(type);
        boolean typeIsException = type != null && EXCEPTION_TYPES.contains(type);
        if (type != null && !typeIsClaim && !typeIsException) {
            throw new InvalidRequestException("Type " + type + " is neither an exception type nor a claim type.");
        }

        boolean wantExceptions = (filter.kind() == null || filter.kind() == IncidentKind.EXCEPTION)
                && (type == null || typeIsException);
        boolean wantClaims = (filter.kind() == null || filter.kind() == IncidentKind.CLAIM)
                && (type == null || typeIsClaim);
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean truncated = false;

        List<ExceptionIncident> exceptions = List.of();
        if (wantExceptions) {
            Boolean open = switch (group) {
                case OPEN -> Boolean.TRUE;
                case CLOSED -> Boolean.FALSE;
                case ALL -> null;
            };
            exceptions = incidents.findExceptions(scope.companyId(), open, filter.carrierId(), type, from, to,
                    limit + 1);
            if (exceptions.size() > limit) {
                truncated = true;
                exceptions = exceptions.subList(0, limit);
            }
        }
        List<FreightClaim> claims = List.of();
        if (wantClaims) {
            claims = claimRepository.findAll(claimSpec(scope, group, filter.carrierId(), type, from, to),
                    PageRequest.of(0, limit + 1, Sort.by(Sort.Order.desc("openedAt")))).getContent();
            if (claims.size() > limit) {
                truncated = true;
                claims = claims.subList(0, limit);
            }
        }

        Set<UUID> carrierIds = new HashSet<>();
        exceptions.forEach(exception -> {
            if (exception.carrierId() != null) {
                carrierIds.add(exception.carrierId());
            }
        });
        claims.forEach(claim -> carrierIds.add(claim.carrierId()));
        Map<UUID, String> carrierNames = claimService.carrierNamesOf(scope, carrierIds);
        Map<UUID, ClaimableTrip> trips = incidents.findTrips(
                claims.stream().map(FreightClaim::tripId).collect(Collectors.toSet()), scope.companyId());
        Map<UUID, List<String>> claimsByException = exceptions.isEmpty() ? Map.of()
                : claimRepository.findByCompanyIdAndTripExceptionIdIn(scope.companyId(),
                                exceptions.stream().map(ExceptionIncident::exceptionId).toList()).stream()
                        .filter(claim -> claim.status() != ClaimStatus.CANCELLED)
                        .collect(Collectors.groupingBy(FreightClaim::tripExceptionId,
                                Collectors.mapping(FreightClaim::claimNumber, Collectors.toList())));

        List<IncidentRow> rows = new ArrayList<>();
        for (ExceptionIncident exception : exceptions) {
            boolean open = "OPEN".equals(exception.status());
            OffsetDateTime end = open || exception.resolvedAt() == null ? now : exception.resolvedAt();
            rows.add(new IncidentRow(IncidentKind.EXCEPTION, exception.exceptionId(), exception.shipmentNumber(),
                    exception.type(), exception.status(), open, exception.tripId(), exception.shipmentNumber(),
                    exception.carrierId(), exception.carrierId() == null ? null : carrierNames.get(exception.carrierId()),
                    exception.reportedAt(), open ? null : exception.resolvedAt(),
                    FreightClaimService.ageDays(exception.reportedAt(), end, scope), null, null, exception.notes(),
                    claimsByException.getOrDefault(exception.exceptionId(), List.of())));
        }
        for (FreightClaim claim : claims) {
            boolean open = claim.status().isActive();
            OffsetDateTime end = claim.closedAt() != null ? claim.closedAt()
                    : claim.status() == ClaimStatus.CANCELLED ? claim.updatedAt() : now;
            BigDecimal amount = claim.acceptedAmount() != null ? claim.acceptedAmount() : claim.claimedAmount();
            rows.add(new IncidentRow(IncidentKind.CLAIM, claim.id(), claim.claimNumber(), claim.claimType().name(),
                    claim.status().name(), open, claim.tripId(),
                    Optional.ofNullable(trips.get(claim.tripId())).map(ClaimableTrip::shipmentNumber).orElse(null),
                    claim.carrierId(), carrierNames.get(claim.carrierId()), claim.openedAt(),
                    open ? null : end, FreightClaimService.ageDays(claim.openedAt(), end, scope), amount,
                    claim.currency(), claim.description(), List.of()));
        }
        // Oldest open first: ageing is the point of the screen, and what has waited longest is what
        // somebody should look at first. Dealt-with rows go after, newest first.
        Comparator<IncidentRow> order = (left, right) -> {
            if (left.open() != right.open()) {
                return left.open() ? -1 : 1;
            }
            return left.open() ? left.occurredAt().compareTo(right.occurredAt())
                    : right.occurredAt().compareTo(left.occurredAt());
        };
        rows.sort(order);

        long openExceptions = rows.stream().filter(row -> row.kind() == IncidentKind.EXCEPTION && row.open()).count();
        long activeClaims = rows.stream().filter(row -> row.kind() == IncidentKind.CLAIM && row.open()).count();
        AgeingBuckets ageing = AgeingBuckets.of(rows.stream().filter(IncidentRow::open).map(IncidentRow::ageDays)
                .toList());
        return new IncidentCentreView(List.copyOf(rows), new IncidentTotals(openExceptions, activeClaims, ageing),
                truncated, limit);
    }

    private static Specification<FreightClaim> claimSpec(CompanyScope scope, StatusGroup group, UUID carrierId,
            String type, OffsetDateTime from, OffsetDateTime to) {
        UUID companyId = scope.companyId();
        Specification<FreightClaim> spec = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
        if (group != StatusGroup.ALL) {
            Set<ClaimStatus> statuses = group == StatusGroup.OPEN ? ACTIVE : FILED;
            spec = spec.and((root, query, cb) -> root.get("status").in(statuses));
        }
        if (carrierId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("carrierId"), carrierId));
        }
        if (type != null) {
            com.ebim.tms.settlement.domain.ClaimType claimType = com.ebim.tms.settlement.domain.ClaimType.valueOf(type);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("claimType"), claimType));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("openedAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("openedAt"), to));
        }
        return spec;
    }

    private static boolean isClaimType(String type) {
        for (com.ebim.tms.settlement.domain.ClaimType candidate : com.ebim.tms.settlement.domain.ClaimType.values()) {
            if (candidate.name().equals(type)) {
                return true;
            }
        }
        return false;
    }
}
