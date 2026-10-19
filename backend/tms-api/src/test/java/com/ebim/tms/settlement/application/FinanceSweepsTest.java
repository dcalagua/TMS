package com.ebim.tms.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.settlement.infrastructure.CostAllocationRepository;
import com.ebim.tms.settlement.infrastructure.FreightAccrualRepository;
import com.ebim.tms.shared.reference.AccruableTripLookupPort;
import com.ebim.tms.shared.reference.AccruableTripLookupPort.AccruableTrip;
import com.ebim.tms.shared.reference.TripCostLookupPort;
import com.ebim.tms.shared.reference.TripCostLookupPort.TripCostSummary;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopeLoader;
import com.ebim.tms.shared.security.TenantOperationGate;
import com.ebim.tms.shared.settings.AccrualTrigger;
import com.ebim.tms.shared.settings.CompanySettings;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The two finance sweeps' company pass (post-review fixes, 2026-10-18): a suspended tenant is not
 * swept, and one trip that throws is logged and counted without abandoning the others.
 */
class FinanceSweepsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "C1", "Company", "America/Lima",
            UUID.fromString("00000000-0000-4000-8000-0000000000a1"), "ORG", "Organization", Set.of(), null);

    private final AccruableTripLookupPort trips = mock(AccruableTripLookupPort.class);
    private final TripCostLookupPort costs = mock(TripCostLookupPort.class);
    private final FreightAccrualRepository accruals = mock(FreightAccrualRepository.class);
    private final AccrualService accrualService = mock(AccrualService.class);
    private final CompanySettingsPort settings = mock(CompanySettingsPort.class);
    private final CompanyScopeLoader scopes = mock(CompanyScopeLoader.class);
    private final CostAllocationRepository allocations = mock(CostAllocationRepository.class);
    private final CostAllocationService allocationService = mock(CostAllocationService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<TenantOperationGate> gate = mock(ObjectProvider.class);

    private final AccruableTrip first = trip("1");
    private final AccruableTrip second = trip("2");

    private static AccruableTrip trip(String suffix) {
        return new AccruableTrip(UUID.nameUUIDFromBytes(suffix.getBytes()), "SH-" + suffix,
                UUID.nameUUIDFromBytes(("carrier" + suffix).getBytes()), null, null,
                OffsetDateTime.now(CLOCK).minusHours(2), null);
    }

    private AccrualSweep accrualSweep() {
        CompanySettings companySettings = mock(CompanySettings.class);
        when(companySettings.accrualTrigger()).thenReturn(AccrualTrigger.DISPATCHED);
        when(settings.settingsOf(COMPANY)).thenReturn(companySettings);
        when(trips.companiesWithExecutionSince(any())).thenReturn(Set.of(COMPANY));
        when(scopes.loadActiveCompanyScope(COMPANY)).thenReturn(Optional.of(SCOPE));
        when(trips.findExecuted(eq(COMPANY), any(), any(), any(), any(), anyInt())).thenReturn(List.of(first, second));
        when(costs.findCosts(any(), eq(COMPANY))).thenReturn(Map.of(
                first.tripId(), cost(first), second.tripId(), cost(second)));
        return new AccrualSweep(trips, costs, accruals, accrualService, settings, scopes,
                mock(PlatformTransactionManager.class), CLOCK, Duration.ofDays(31), gate);
    }

    private static TripCostSummary cost(AccruableTrip trip) {
        return new TripCostSummary(trip.tripId(), new BigDecimal("500.00"), null, "PEN", false, Map.of(), null);
    }

    @Test
    @DisplayName("accrual sweep: a suspended organization is not swept")
    void accrualSkipsSuspended() {
        AccrualSweep sweep = accrualSweep();
        when(gate.getIfAvailable()).thenReturn(scope -> Optional.of(
                new TenantOperationGate.Suspension("NON_PAYMENT", Instant.now(CLOCK))));

        assertThat(sweep.sweepAll()).isEqualTo(AccrualSweepResult.NONE);
        verify(trips, never()).findExecuted(any(), any(), any(), any(), any(), anyInt());
        verify(accrualService, never()).accrue(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("accrual sweep: one trip that throws is counted and the next one is still accrued")
    void accrualIsolatesOneTrip() {
        AccrualSweep sweep = accrualSweep();
        when(gate.getIfAvailable()).thenReturn(scope -> Optional.empty());
        when(accrualService.accrue(any(), any(), eq(first), any(), any())).thenThrow(new IllegalStateException("boom"));
        when(accrualService.accrue(any(), any(), eq(second), any(), any())).thenReturn(true);

        AccrualSweepResult result = sweep.sweepAll();

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    private CostAllocationSweep allocationSweep() {
        when(trips.companiesWithExecutionSince(any())).thenReturn(Set.of(COMPANY));
        when(scopes.loadActiveCompanyScope(COMPANY)).thenReturn(Optional.of(SCOPE));
        when(trips.findExecuted(eq(COMPANY), any(), any(), any(), any(), anyInt())).thenReturn(List.of(first, second));
        when(allocations.findCurrent(eq(COMPANY), any())).thenReturn(Map.of());
        when(costs.findCosts(any(), eq(COMPANY))).thenReturn(Map.of(
                first.tripId(), cost(first), second.tripId(), cost(second)));
        return new CostAllocationSweep(trips, costs, allocations, allocationService, scopes, CLOCK,
                Duration.ofDays(62), gate);
    }

    @Test
    @DisplayName("cost allocation sweep: a suspended organization is not swept")
    void allocationSkipsSuspended() {
        CostAllocationSweep sweep = allocationSweep();
        when(gate.getIfAvailable()).thenReturn(scope -> Optional.of(
                new TenantOperationGate.Suspension("MANUAL", Instant.now(CLOCK))));

        assertThat(sweep.sweepAll().allocated()).isZero();
        verify(trips, never()).findExecuted(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("cost allocation sweep: one trip that throws is counted and the next one is still allocated")
    void allocationIsolatesOneTrip() {
        CostAllocationSweep sweep = allocationSweep();
        when(gate.getIfAvailable()).thenReturn(null);
        when(allocationService.allocateBySweep(any(), eq(first.tripId()), any()))
                .thenThrow(new IllegalStateException("boom"));
        when(allocationService.allocateBySweep(any(), eq(second.tripId()), any())).thenReturn(true);

        CostAllocationSweep.Result result = sweep.sweepAll();

        assertThat(result.allocated()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }
}
