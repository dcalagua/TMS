package com.ebim.tms.rates.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ebim.tms.rates.domain.RateCard;
import com.ebim.tms.rates.domain.RateCardScope;
import com.ebim.tms.rates.domain.RateComponents;
import com.ebim.tms.rates.infrastructure.RateCardRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.VehicleTypeLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two rules {@link RateCardService} owns that neither the entity nor the schema can state in a
 * way a commercial user could act on: the scope trio, and the refusal of an overlapping agreement.
 */
class RateCardServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID OTHER_CARRIER = UUID.randomUUID();
    private static final UUID ORIGIN = UUID.randomUUID();
    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID VEHICLE_TYPE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private RateCardRepository rateCardRepository;
    private RateCardService service;

    @BeforeEach
    void setUp() {
        rateCardRepository = mock(RateCardRepository.class);
        CarrierLookupPort carriers = mock(CarrierLookupPort.class);
        OriginLookupPort origins = mock(OriginLookupPort.class);
        DestinationLookupPort destinations = mock(DestinationLookupPort.class);
        RouteTemplateLookupPort routes = mock(RouteTemplateLookupPort.class);
        VehicleTypeLookupPort vehicleTypes = mock(VehicleTypeLookupPort.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(ACTOR);

        service = new RateCardService(rateCardRepository, carriers, origins, destinations, routes,
                vehicleTypes, actors, mock(AuditRecorder.class));

        when(carriers.findActiveInCompany(CARRIER, COMPANY))
                .thenReturn(Optional.of(MasterReference.of(CARRIER, "CAR-1", "Transportes Uno")));
        when(carriers.findActiveInCompany(OTHER_CARRIER, COMPANY))
                .thenReturn(Optional.of(MasterReference.of(OTHER_CARRIER, "CAR-2", "Transportes Dos")));
        when(origins.findActiveInCompany(ORIGIN, COMPANY))
                .thenReturn(Optional.of(MasterReference.of(ORIGIN, "DEP-1", "Depot One")));
        when(routes.findActiveInCompany(ROUTE, COMPANY))
                .thenReturn(Optional.of(new RouteTemplate(ROUTE, "RT-1", "Norte", ORIGIN, List.of(), true)));
        when(vehicleTypes.findActiveInCompany(VEHICLE_TYPE, COMPANY))
                .thenReturn(Optional.of(MasterReference.of(VEHICLE_TYPE, "TRUCK-8T", "Truck 8t")));
        when(carriers.findAllInCompany(anySet(), eq(COMPANY))).thenReturn(Map.of());
        when(origins.findAllInCompany(anySet(), eq(COMPANY))).thenReturn(Map.of());
        when(routes.findAllInCompany(anySet(), eq(COMPANY))).thenReturn(Map.of());
        when(vehicleTypes.findAllInCompany(anySet(), eq(COMPANY))).thenReturn(Map.of());

        when(rateCardRepository.existsByCompanyIdAndCode(eq(COMPANY), any())).thenReturn(false);
        when(rateCardRepository.findByCompanyIdAndCarrierIdAndActiveTrue(COMPANY, CARRIER)).thenReturn(List.of());
        when(rateCardRepository.saveAndFlush(any(RateCard.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Nested
    @DisplayName("the scope trio")
    class Scope {

        @Test
        @DisplayName("a carrier-wide card must name neither an origin nor a route")
        void carrierScopeTakesNoTarget() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.create(SCOPE,
                            request("C1", RateCardScope.CARRIER, ORIGIN, null, null, JANUARY, null)))
                    .withMessageContaining("originId must not be set");
        }

        @Test
        @DisplayName("an origin-scoped card must name one")
        void originScopeNeedsATarget() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.create(SCOPE,
                            request("C1", RateCardScope.ORIGIN, null, null, null, JANUARY, null)))
                    .withMessageContaining("originId is required");
        }

        @Test
        @DisplayName("a route-scoped card is stored against the route and nothing else")
        void routeScope() {
            RateCardView view = service.create(SCOPE,
                    request("C1", RateCardScope.ROUTE, null, ROUTE, VEHICLE_TYPE, JANUARY, null));

            assertThat(view.scope()).isEqualTo(RateCardScope.ROUTE);
            assertThat(view.scopeTargetId()).isEqualTo(ROUTE);
            assertThat(view.vehicleTypeId()).isEqualTo(VEHICLE_TYPE);
        }

        @Test
        @DisplayName("a master this company cannot use is refused before anything is written")
        void unknownMaster() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.create(SCOPE,
                            request("C1", RateCardScope.ORIGIN, UUID.randomUUID(), null, null, JANUARY, null)))
                    .withMessageContaining("active origin");
        }
    }

    @Nested
    @DisplayName("what a card must charge")
    class Components {

        @Test
        @DisplayName("a card with no component at all is not a rate")
        void needsAComponent() {
            RateCardRequest empty = new RateCardRequest("C1", "Card one", CARRIER, RateCardScope.CARRIER, null, null,
                    null, "PEN", JANUARY, null, null, null, null, null, null, null);

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.create(SCOPE, empty))
                    .withMessageContaining("at least one charge: baseAmount");
        }

        @Test
        @DisplayName("a floor with nothing to raise does not count as a component either")
        void minimumAloneIsNotAComponent() {
            RateCardRequest floorOnly = new RateCardRequest("C1", "Card one", CARRIER, RateCardScope.CARRIER, null,
                    null, null, "PEN", JANUARY, null, null, null, null, null, null, new BigDecimal("90.00"));

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.create(SCOPE, floorOnly))
                    .withMessageContaining("at least one charge: baseAmount");
        }

        @Test
        @DisplayName("validTo before validFrom is refused")
        void backwardsValidity() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.create(SCOPE, request("C1", RateCardScope.CARRIER, null, null, null,
                            JANUARY, JANUARY.minusDays(1))))
                    .withMessageContaining("validTo cannot be before validFrom");
        }
    }

    @Nested
    @DisplayName("overlapping agreements")
    class Overlap {

        @Test
        @DisplayName("two agreements for the same carrier and scope may not be in force at once")
        void refusesAnOverlap() {
            when(rateCardRepository.findByCompanyIdAndCarrierIdAndActiveTrue(COMPANY, CARRIER))
                    .thenReturn(List.of(existing(RateCardScope.CARRIER, null, null, null, JANUARY, null)));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.create(SCOPE, request("C2", RateCardScope.CARRIER, null, null, null,
                            JANUARY.plusMonths(3), null)))
                    .withMessageContaining("EXISTING");
        }

        @Test
        @DisplayName("consecutive periods are not an overlap")
        void allowsASuccessor() {
            when(rateCardRepository.findByCompanyIdAndCarrierIdAndActiveTrue(COMPANY, CARRIER))
                    .thenReturn(List.of(existing(RateCardScope.CARRIER, null, null, null, JANUARY,
                            JANUARY.plusMonths(6).minusDays(1))));

            RateCardView view = service.create(SCOPE, request("C2", RateCardScope.CARRIER, null, null, null,
                    JANUARY.plusMonths(6), null));

            assertThat(view.code()).isEqualTo("C2");
        }

        @Test
        @DisplayName("a different scope, target or vehicle type is a different agreement")
        void differentAgreementsCoexist() {
            when(rateCardRepository.findByCompanyIdAndCarrierIdAndActiveTrue(COMPANY, CARRIER))
                    .thenReturn(List.of(existing(RateCardScope.CARRIER, null, null, null, JANUARY, null)));

            assertThat(service.create(SCOPE,
                    request("C2", RateCardScope.ROUTE, null, ROUTE, null, JANUARY, null)).code()).isEqualTo("C2");
            assertThat(service.create(SCOPE,
                    request("C3", RateCardScope.CARRIER, null, null, VEHICLE_TYPE, JANUARY, null)).code())
                    .isEqualTo("C3");
        }
    }

    @Test
    @DisplayName("a card cannot be moved to another carrier: that is a new agreement")
    void carrierIsImmutable() {
        RateCard card = existing(RateCardScope.CARRIER, null, null, null, JANUARY, null);
        UUID id = UUID.randomUUID();
        when(rateCardRepository.findByIdAndCompanyId(id, COMPANY)).thenReturn(Optional.of(card));

        RateCardRequest moved = new RateCardRequest("EXISTING", "Existing", OTHER_CARRIER, RateCardScope.CARRIER,
                null, null, null, "PEN", JANUARY, null, new BigDecimal("100.00"), null, null, null, null, null);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> service.update(SCOPE, id, moved))
                .withMessageContaining("another carrier");
    }

    private static RateCardRequest request(String code, RateCardScope scope, UUID originId, UUID routeId,
            UUID vehicleTypeId, LocalDate validFrom, LocalDate validTo) {
        return new RateCardRequest(code, "Card " + code, CARRIER, scope, originId, routeId, vehicleTypeId, "PEN",
                validFrom, validTo, new BigDecimal("100.00"), null, null, null, null, null);
    }

    private static RateCard existing(RateCardScope scope, UUID originId, UUID routeId, UUID vehicleTypeId,
            LocalDate validFrom, LocalDate validTo) {
        return new RateCard(COMPANY, "EXISTING", "Existing", CARRIER, scope, originId, routeId, vehicleTypeId,
                "PEN", validFrom, validTo, RateComponents.flat(new BigDecimal("100.00")), ACTOR);
    }
}
