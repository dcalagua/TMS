package com.ebim.tms.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.TripExceptionType;
import com.ebim.tms.settlement.application.FreightClaimViews.AgeingBuckets;
import com.ebim.tms.settlement.domain.ClaimType;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pure parts of the incident centre and of the claim draft (V71). */
class IncidentCentreServiceTest {

    @Test
    @DisplayName("the exception types settlement spells out are exactly planning's TripExceptionType")
    void exceptionTypesMatchPlanning() {
        assertThat(IncidentCentreService.EXCEPTION_TYPES).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(TripExceptionType.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("every delivery result and exception type suggests some claim type")
    void everySourceSuggestsAType() {
        for (DeliveryResult result : DeliveryResult.values()) {
            assertThat(FreightClaimService.typeForDelivery(result.name())).isNotNull();
        }
        for (TripExceptionType type : TripExceptionType.values()) {
            assertThat(FreightClaimService.typeForException(type.name())).isNotNull();
        }
        assertThat(FreightClaimService.typeForDelivery("PARTIAL")).isEqualTo(ClaimType.SHORTAGE);
        assertThat(FreightClaimService.typeForDelivery("REJECTED")).isEqualTo(ClaimType.DAMAGE);
        assertThat(FreightClaimService.typeForException("TRAFFIC_DELAY")).isEqualTo(ClaimType.DELAY);
    }

    @Test
    @DisplayName("age is counted in the company's calendar days, and bucketed 0-7, 8-30, 31-60, over 60")
    void ageing() {
        CompanyScope scope = new CompanyScope(UUID.randomUUID(), "C", "C", "America/Lima", UUID.randomUUID(),
                "O", "O", java.util.Set.of());
        // 23:50 in Lima and 00:10 the next day are one calendar day apart there.
        OffsetDateTime late = OffsetDateTime.parse("2026-10-21T23:50:00-05:00");
        OffsetDateTime early = OffsetDateTime.parse("2026-10-22T00:10:00-05:00");
        assertThat(FreightClaimService.ageDays(late, early, scope)).isEqualTo(1);
        assertThat(FreightClaimService.ageDays(early, late, scope)).isZero();
        assertThat(AgeingBuckets.of(List.of(0L, 7L, 8L, 30L, 31L, 60L, 61L, 400L)))
                .isEqualTo(new AgeingBuckets(2, 2, 2, 2));
    }
}
