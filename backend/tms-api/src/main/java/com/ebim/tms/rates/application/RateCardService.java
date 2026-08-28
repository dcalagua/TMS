package com.ebim.tms.rates.application;

import com.ebim.tms.rates.domain.RateCard;
import com.ebim.tms.rates.domain.RateCardScope;
import com.ebim.tms.rates.domain.RateComponents;
import com.ebim.tms.rates.infrastructure.RateCardRepository;
import com.ebim.tms.rates.infrastructure.RateCardSpecifications;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.VehicleTypeLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate card use cases. Takes a {@link CompanyScope}, never a company id - the contract every
 * service in this codebase follows.
 *
 * <p>Two rules live here rather than in the entity or the schema, because both need to produce a
 * message a commercial user can act on:
 *
 * <ul>
 *   <li><b>The scope target</b> - exactly one of origin/route, decided by the scope - and that the
 *       master it names is active and this company's. The database guarantees the shape
 *       ({@code ck_rate_card_scope_target}) and the tenancy (composite FKs); neither can say
 *       "route NORTE-01 is deactivated".</li>
 *   <li><b>No overlapping agreement</b>: two active cards for the same carrier, scope, target and
 *       vehicle type whose validity periods intersect. {@code uq_rate_card_active_agreement}
 *       catches only the exact-duplicate race; the readable refusal is here, and it names the card
 *       that collides.</li>
 * </ul>
 */
@Service
public class RateCardService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("code", "name", "currency", "validFrom", "validTo", "active", "createdAt", "updatedAt");

    private final RateCardRepository rateCardRepository;
    private final CarrierLookupPort carrierLookupPort;
    private final OriginLookupPort originLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final RouteTemplateLookupPort routeTemplateLookupPort;
    private final VehicleTypeLookupPort vehicleTypeLookupPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public RateCardService(RateCardRepository rateCardRepository, CarrierLookupPort carrierLookupPort,
            OriginLookupPort originLookupPort,
            DestinationLookupPort destinationLookupPort, RouteTemplateLookupPort routeTemplateLookupPort,
            VehicleTypeLookupPort vehicleTypeLookupPort, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder) {
        this.rateCardRepository = rateCardRepository;
        this.carrierLookupPort = carrierLookupPort;
        this.originLookupPort = originLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.routeTemplateLookupPort = routeTemplateLookupPort;
        this.vehicleTypeLookupPort = vehicleTypeLookupPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public PageResponse<RateCardView> list(CompanyScope scope, RateCardFilter filter, PageQuery pageQuery) {
        var specification = RateCardSpecifications.matching(scope.companyId(), filter.code(), filter.name(),
                filter.carrierId(), filter.scope(), filter.vehicleTypeId(), filter.currency(), filter.onDate(),
                filter.active());
        Page<RateCard> page = rateCardRepository.findAll(specification, toPageable(pageQuery));
        List<RateCardView> content = toViews(page.getContent(), scope.companyId());
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public RateCardView get(CompanyScope scope, UUID id) {
        return toViews(List.of(find(scope, id)), scope.companyId()).getFirst();
    }

    @Transactional
    public RateCardView create(CompanyScope scope, RateCardRequest request) {
        String code = normalizeCode(request.code());
        String currency = normalizeCurrency(request.currency());
        ScopeTarget target = validateScope(scope, request);
        requireValidity(request);
        RateComponents components = requireComponents(request);
        requireActiveCarrier(scope, request.carrierId());
        requireActiveVehicleType(scope, request.vehicleTypeId());
        if (rateCardRepository.existsByCompanyIdAndCode(scope.companyId(), code)) {
            throw duplicateCode(code);
        }
        requireNoOverlap(scope, request, target, null);

        UUID actorId = auditActorProvider.requireAppUserId();
        RateCard card = new RateCard(scope.companyId(), code, request.name().trim(), request.carrierId(),
                request.scope(), target.originId(), target.destinationId(), target.routeId(),
                request.vehicleTypeId(), currency, request.validFrom(), request.validTo(), components, actorId);
        RateCard saved = saveOrConflict(card, code);
        auditRecorder.record(scope, AuditAggregateType.RATE_CARD, saved.id(), AuditAction.CREATE,
                Map.of("code", saved.code(), "scope", saved.scope().name(), "currency", saved.currency()));
        return toViews(List.of(saved), scope.companyId()).getFirst();
    }

    @Transactional
    public RateCardView update(CompanyScope scope, UUID id, RateCardRequest request) {
        RateCard card = find(scope, id);
        String code = normalizeCode(request.code());
        String currency = normalizeCurrency(request.currency());
        ScopeTarget target = validateScope(scope, request);
        requireValidity(request);
        RateComponents components = requireComponents(request);
        // Deliberately refused rather than ignored: a card is an agreement with one counterparty,
        // and moving it to another would restate every estimate that has already cited it. The
        // answer is a new card, which is also what actually happened commercially.
        if (!card.carrierId().equals(request.carrierId())) {
            throw new ConflictException("A rate card cannot be moved to another carrier. Create a new card instead.");
        }
        requireActiveVehicleType(scope, request.vehicleTypeId());
        if (rateCardRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, id)) {
            throw duplicateCode(code);
        }
        requireNoOverlap(scope, request, target, id);

        UUID actorId = auditActorProvider.requireAppUserId();
        card.applyChanges(code, request.name().trim(), request.scope(), target.originId(),
                target.destinationId(), target.routeId(), request.vehicleTypeId(), currency,
                request.validFrom(), request.validTo(), components, actorId);
        RateCard saved = saveOrConflict(card, code);
        auditRecorder.record(scope, AuditAggregateType.RATE_CARD, saved.id(), AuditAction.UPDATE,
                Map.of("code", saved.code(), "scope", saved.scope().name(), "currency", saved.currency()));
        return toViews(List.of(saved), scope.companyId()).getFirst();
    }

    /**
     * Brings a card back into force - and re-checks the overlap rule while doing it, because the
     * rule only ever constrained <em>active</em> cards. Two agreements that were allowed to sit
     * side by side while one of them was switched off must not both become live just because
     * somebody pressed activate on the older one.
     */
    @Transactional
    public RateCardView activate(CompanyScope scope, UUID id) {
        RateCard card = find(scope, id);
        requireNoOverlapWith(scope, card);
        card.activate(auditActorProvider.requireAppUserId());
        RateCard saved = rateCardRepository.saveAndFlush(card);
        auditRecorder.record(scope, AuditAggregateType.RATE_CARD, saved.id(), AuditAction.ACTIVATE,
                Map.of("code", saved.code()));
        return toViews(List.of(saved), scope.companyId()).getFirst();
    }

    /**
     * Takes a card out of force. Never a delete: estimates already calculated from it keep pointing
     * at it ({@code fk_trip_cost_rate_card} makes deletion impossible anyway), and they keep the
     * snapshot of what it said, so nothing they show changes.
     */
    @Transactional
    public RateCardView deactivate(CompanyScope scope, UUID id) {
        RateCard card = find(scope, id);
        card.deactivate(auditActorProvider.requireAppUserId());
        RateCard saved = rateCardRepository.saveAndFlush(card);
        auditRecorder.record(scope, AuditAggregateType.RATE_CARD, saved.id(), AuditAction.DEACTIVATE,
                Map.of("code", saved.code()));
        return toViews(List.of(saved), scope.companyId()).getFirst();
    }

    RateCard find(CompanyScope scope, UUID id) {
        return rateCardRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Rate card not found."));
    }

    /**
     * Resolves the four referenced masters for a page of cards in four batched calls, whatever the
     * page size.
     */
    private List<RateCardView> toViews(List<RateCard> cards, UUID companyId) {
        if (cards.isEmpty()) {
            return List.of();
        }
        Map<UUID, MasterReference> carriers = carrierLookupPort.findAllInCompany(
                idsOf(cards, RateCard::carrierId), companyId);
        Map<UUID, MasterReference> origins = originLookupPort.findAllInCompany(
                idsOf(cards, RateCard::originId), companyId);
        Map<UUID, MasterReference> vehicleTypes = vehicleTypeLookupPort.findAllInCompany(
                idsOf(cards, RateCard::vehicleTypeId), companyId);
        Map<UUID, MasterReference> routes = new HashMap<>();
        routeTemplateLookupPort.findAllInCompany(idsOf(cards, RateCard::routeId), companyId)
                .forEach((routeId, route) -> routes.put(routeId, toReference(route)));
        // The lane's far end, batched with everything else: a page of lane cards must not cost a
        // lookup per row.
        Map<UUID, MasterReference> destinations = destinationLookupPort.findAllInCompany(
                idsOf(cards, RateCard::destinationId), companyId);

        return cards.stream()
                .map(card -> RateCardView.from(card,
                        lookup(carriers, card.carrierId()),
                        card.scope() == RateCardScope.ROUTE
                                ? lookup(routes, card.routeId())
                                : lookup(origins, card.originId()),
                        lookup(vehicleTypes, card.vehicleTypeId()),
                        lookup(destinations, card.destinationId())))
                .toList();
    }

    /**
     * A resolved name for {@code id}, or null when there is no id to resolve.
     *
     * <p>The null check is the point. A CARRIER-scoped card has no origin and no route, and the
     * maps here are keyed by the ids that were actually asked for - so the scope's target is
     * looked up with a null key on exactly the cards that are correct. {@code Map.of()}, which is
     * what a lookup port returns when the id set was empty, throws on a null key rather than
     * answering "not found", so the whole list of cards fails to render because one of them is a
     * carrier-wide agreement. Asking the map at all is the mistake, not the map's answer.
     */
    private static MasterReference lookup(Map<UUID, MasterReference> references, UUID id) {
        return id == null ? null : references.get(id);
    }

    private static MasterReference toReference(RouteTemplate route) {
        return MasterReference.of(route.id(), route.code(), route.name());
    }

    private static Set<UUID> idsOf(List<RateCard> cards, Function<RateCard, UUID> extractor) {
        return cards.stream().map(extractor).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * Checks the scope trio and that the master it names may be pointed at: exactly one target,
     * matching the scope, active and in this company.
     */
    private ScopeTarget validateScope(CompanyScope scope, RateCardRequest request) {
        return switch (request.scope()) {
            case CARRIER -> {
                requireAbsent(request.originId(), "originId", RateCardScope.CARRIER);
                requireAbsent(request.destinationId(), "destinationId", RateCardScope.CARRIER);
                requireAbsent(request.routeId(), "routeId", RateCardScope.CARRIER);
                yield new ScopeTarget(null, null, null);
            }
            case ORIGIN -> {
                requireAbsent(request.routeId(), "routeId", RateCardScope.ORIGIN);
                requireAbsent(request.destinationId(), "destinationId", RateCardScope.ORIGIN);
                UUID originId = requirePresent(request.originId(), "originId", RateCardScope.ORIGIN);
                originLookupPort.findActiveInCompany(originId, scope.companyId())
                        .orElseThrow(() -> new InvalidRequestException(
                                "originId does not name an active origin of this company."));
                yield new ScopeTarget(originId, null, null);
            }
            case LANE -> {
                requireAbsent(request.routeId(), "routeId", RateCardScope.LANE);
                UUID originId = requirePresent(request.originId(), "originId", RateCardScope.LANE);
                UUID destinationId = requirePresent(request.destinationId(), "destinationId", RateCardScope.LANE);
                originLookupPort.findActiveInCompany(originId, scope.companyId())
                        .orElseThrow(() -> new InvalidRequestException(
                                "originId does not name an active origin of this company."));
                destinationLookupPort.findActiveInCompany(destinationId, scope.companyId())
                        .orElseThrow(() -> new InvalidRequestException(
                                "destinationId does not name an active destination of this company."));
                yield new ScopeTarget(originId, destinationId, null);
            }
            case ROUTE -> {
                requireAbsent(request.originId(), "originId", RateCardScope.ROUTE);
                requireAbsent(request.destinationId(), "destinationId", RateCardScope.ROUTE);
                UUID routeId = requirePresent(request.routeId(), "routeId", RateCardScope.ROUTE);
                routeTemplateLookupPort.findActiveInCompany(routeId, scope.companyId())
                        .orElseThrow(() -> new InvalidRequestException(
                                "routeId does not name an active route of this company."));
                yield new ScopeTarget(null, null, routeId);
            }
        };
    }

    private void requireActiveCarrier(CompanyScope scope, UUID carrierId) {
        carrierLookupPort.findActiveInCompany(carrierId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException(
                        "carrierId does not name an active carrier of this company."));
    }

    private void requireActiveVehicleType(CompanyScope scope, UUID vehicleTypeId) {
        if (vehicleTypeId == null) {
            return;
        }
        vehicleTypeLookupPort.findActiveInCompany(vehicleTypeId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException(
                        "vehicleTypeId does not name an active vehicle type of this company."));
    }

    private static void requireValidity(RateCardRequest request) {
        if (request.validTo() != null && request.validTo().isBefore(request.validFrom())) {
            throw new InvalidRequestException("validTo cannot be before validFrom.");
        }
    }

    /**
     * A card must charge for something. {@code minimumAmount} alone does not count: a floor with
     * nothing to raise is not a tariff, it is a fee, and calling it a rate card would make every
     * shipment of that carrier cost exactly the minimum with no line explaining why.
     */
    private static RateComponents requireComponents(RateCardRequest request) {
        // The label and the amount travel together (ck_rate_card_accessorial_pair): a labelled
        // nothing and an unlabelled charge are both rows nobody can approve.
        String label = request.accessorialLabel() == null || request.accessorialLabel().isBlank()
                ? null
                : request.accessorialLabel().trim();
        if ((request.accessorialAmount() == null) != (label == null)) {
            throw new InvalidRequestException(
                    "accessorialAmount and accessorialLabel must be given together: a charge nobody "
                            + "can name is a charge nobody can approve.");
        }
        if (request.maximumAmount() != null && request.minimumAmount() != null
                && request.maximumAmount().compareTo(request.minimumAmount()) < 0) {
            throw new InvalidRequestException("maximumAmount cannot be below minimumAmount.");
        }

        RateComponents components = new RateComponents(request.baseAmount(), request.amountPerKm(),
                request.amountPerKg(), request.amountPerM3(), request.amountPerPallet(), request.minimumAmount(),
                request.amountPerStop(), request.fuelSurchargePercent(), request.amountPerWaitingHour(),
                request.tollAmount(), request.accessorialAmount(), label, request.maximumAmount());
        // The floor and the ceiling are deliberately not on this list, for the reason
        // RateCard.hasAnyComponent gives: a limit is a rule about other charges, not a charge.
        boolean hasComponent = components.baseAmount() != null || components.amountPerKm() != null
                || components.amountPerKg() != null || components.amountPerM3() != null
                || components.amountPerPallet() != null || components.amountPerStop() != null
                || components.fuelSurchargePercent() != null || components.amountPerWaitingHour() != null
                || components.tollAmount() != null || components.accessorialAmount() != null;
        if (!hasComponent) {
            throw new InvalidRequestException(
                    "A rate card must define at least one charge: baseAmount, amountPerKm, amountPerKg, "
                            + "amountPerM3, amountPerPallet, amountPerStop, fuelSurchargePercent, "
                            + "amountPerWaitingHour, tollAmount or accessorialAmount.");
        }
        return components;
    }

    private void requireNoOverlap(CompanyScope scope, RateCardRequest request, ScopeTarget target, UUID exceptId) {
        findOverlapping(scope, request.carrierId(), request.scope(), target.originId(), target.routeId(),
                request.vehicleTypeId(), request.validFrom(), request.validTo(), exceptId);
    }

    private void requireNoOverlapWith(CompanyScope scope, RateCard card) {
        findOverlapping(scope, card.carrierId(), card.scope(), card.originId(), card.routeId(), card.vehicleTypeId(),
                card.validFrom(), card.validTo(), card.id());
    }

    /**
     * Refuses an agreement whose validity intersects one already in force for the same carrier,
     * scope, target and vehicle type - naming the card it collides with, because "which one" is
     * the only useful part of that answer.
     *
     * <p>Compared in Java over one carrier's active cards rather than in a WHERE clause: the same
     * set {@code RateCardSelector} works on, so "cards that overlap" and "cards that could be
     * selected" are decided by the same, small, testable definition.
     */
    private void findOverlapping(CompanyScope scope, UUID carrierId, RateCardScope cardScope, UUID originId,
            UUID routeId, UUID vehicleTypeId, LocalDate validFrom, LocalDate validTo, UUID exceptId) {
        rateCardRepository.findByCompanyIdAndCarrierIdAndActiveTrue(scope.companyId(), carrierId).stream()
                // exceptId is null on create, when there is nothing to exclude. Written this way
                // round rather than !existing.id().equals(exceptId) so that a card whose id has
                // not been assigned yet is still compared instead of raising.
                .filter(existing -> exceptId == null || !exceptId.equals(existing.id()))
                .filter(existing -> existing.isSameAgreementAs(carrierId, cardScope, originId, routeId, vehicleTypeId))
                .filter(existing -> existing.overlaps(validFrom, validTo))
                .findFirst()
                .ifPresent(existing -> {
                    throw new ConflictException("Rate card '" + existing.code()
                            + "' already covers this carrier and scope between " + existing.validFrom()
                            + " and " + (existing.validTo() == null ? "no end date" : existing.validTo()) + ".");
                });
    }

    private RateCard saveOrConflict(RateCard card, String code) {
        try {
            return rateCardRepository.saveAndFlush(card);
        } catch (DataIntegrityViolationException raced) {
            // Either uq_rate_card_company_code or uq_rate_card_active_agreement: both mean somebody
            // else entered the same agreement between this transaction's check and its write.
            throw duplicateCode(code);
        }
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("A rate card with code '" + code + "' already exists in this company.");
    }

    private static void requireAbsent(UUID value, String field, RateCardScope scope) {
        if (value != null) {
            throw new InvalidRequestException(field + " must not be set for a " + scope + "-scoped rate card.");
        }
    }

    private static UUID requirePresent(UUID value, String field, RateCardScope scope) {
        if (value == null) {
            throw new InvalidRequestException(field + " is required for a " + scope + "-scoped rate card.");
        }
        return value;
    }

    private static String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeCurrency(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                ? Sort.by(Sort.Direction.ASC, "code")
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }

    /** The scope's resolved target: exactly one of the two is non-null, or neither for {@code CARRIER}. */
    private record ScopeTarget(UUID originId, UUID destinationId, UUID routeId) {
    }
}
