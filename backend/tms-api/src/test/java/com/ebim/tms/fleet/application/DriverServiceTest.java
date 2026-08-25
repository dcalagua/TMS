package com.ebim.tms.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.fleet.domain.Carrier;
import com.ebim.tms.fleet.domain.Driver;
import com.ebim.tms.fleet.infrastructure.CarrierRepository;
import com.ebim.tms.fleet.infrastructure.DriverRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.DriverLicenseStatus;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules {@link DriverService} owns above its repository: normalization, the three uniqueness
 * checks that depend on it, tenant scoping, and the licence status the view derives.
 *
 * <p>Mocked rather than database-backed - the constraint half of the same rules is proved against
 * a real PostgreSQL by {@code FleetApiIntegrationTest}, which needs Docker. This file runs
 * everywhere, which is the point: normalization is what makes those constraints work at all, so it
 * must not be provable only where the database is.
 */
class DriverServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private DriverRepository driverRepository;
    private CarrierRepository carrierRepository;
    private DriverService service;

    @BeforeEach
    void setUp() {
        driverRepository = mock(DriverRepository.class);
        carrierRepository = mock(CarrierRepository.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(ACTOR);

        service = new DriverService(driverRepository, carrierRepository, actors, mock(AuditRecorder.class));
        when(driverRepository.saveAndFlush(any(Driver.class))).thenAnswer(call -> call.getArgument(0));
        when(driverRepository.save(any(Driver.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static DriverRequest request(String code, String documentNumber, String licenseNumber,
            LocalDate licenseExpiresOn, UUID carrierId) {
        return new DriverRequest(code, "  Ana  ", "Quispe", "dni", documentNumber, "  +51 999 111 222 ",
                licenseNumber, "a-iib", licenseExpiresOn, carrierId);
    }

    @Test
    @DisplayName("normalizes the code, document and licence, and trims the name without upper-casing it")
    void normalizesTheIdentifyingFields() {
        DriverView view = service.create(SCOPE, request("dr-ana", "  12345678 ", "q-987654", null, null));

        assertThat(view.code()).isEqualTo("DR-ANA");
        assertThat(view.documentType()).isEqualTo("DNI");
        assertThat(view.documentNumber()).isEqualTo("12345678");
        assertThat(view.licenseNumber()).isEqualTo("Q-987654");
        assertThat(view.licenseCategory()).isEqualTo("A-IIB");
        // A manifest prints this. Trimmed, never shouted.
        assertThat(view.firstName()).isEqualTo("Ana");
        assertThat(view.fullName()).isEqualTo("Quispe, Ana");
        assertThat(view.phone()).isEqualTo("+51 999 111 222");
    }

    @Test
    @DisplayName("an empty licence category becomes null rather than a blank the CHECK would reject")
    void blankOptionalsBecomeNull() {
        DriverView view = service.create(SCOPE,
                new DriverRequest("dr-b", "Ana", "Quispe", "DNI", "12345679", "  ", "Q-2", "   ", null, null));

        assertThat(view.licenseCategory()).isNull();
        assertThat(view.phone()).isNull();
    }

    @Test
    @DisplayName("derives the licence status against the company's own today, not the server's")
    void derivesLicenceStatus() {
        LocalDate today = SCOPE.today();

        assertThat(service.create(SCOPE, request("dr-1", "1", "Q-1", null, null)).licenseStatus())
                .isEqualTo(DriverLicenseStatus.UNRECORDED);
        assertThat(service.create(SCOPE, request("dr-2", "2", "Q-2", today.minusDays(1), null)).licenseStatus())
                .isEqualTo(DriverLicenseStatus.EXPIRED);
        assertThat(service.create(SCOPE, request("dr-3", "3", "Q-3", today.plusYears(1), null)).licenseStatus())
                .isEqualTo(DriverLicenseStatus.VALID);
    }

    @Test
    @DisplayName("refuses a duplicate code, document or licence number with a sentence naming the value")
    void refusesEachDuplicateSeparately() {
        when(driverRepository.existsByCompanyIdAndCode(COMPANY, "DR-DUP")).thenReturn(true);
        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> service.create(SCOPE, request("dr-dup", "1", "Q-1", null, null)))
                .withMessageContaining("DR-DUP");

        when(driverRepository.existsByCompanyIdAndDocumentTypeAndDocumentNumber(COMPANY, "DNI", "12345678"))
                .thenReturn(true);
        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> service.create(SCOPE, request("dr-fresh", "12345678", "Q-1", null, null)))
                .withMessageContaining("12345678");

        when(driverRepository.existsByCompanyIdAndLicenseNumber(COMPANY, "Q-987654")).thenReturn(true);
        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> service.create(SCOPE, request("dr-fresh2", "99", "q-987654", null, null)))
                .withMessageContaining("Q-987654");

        verify(driverRepository, never()).saveAndFlush(any(Driver.class));
    }

    @Test
    @DisplayName("normalizes before checking uniqueness, so 'dni' and 'DNI' collide as one document")
    void uniquenessIsCheckedOnTheNormalizedValue() {
        when(driverRepository.existsByCompanyIdAndDocumentTypeAndDocumentNumber(COMPANY, "DNI", "AB-1"))
                .thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> service.create(SCOPE, request("dr-x", "ab-1", "Q-9", null, null)));
    }

    @Test
    @DisplayName("refuses a carrier that does not resolve inside this company")
    void carrierMustBeInScope() {
        when(carrierRepository.findByIdAndCompanyId(CARRIER, COMPANY)).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.create(SCOPE, request("dr-x", "1", "Q-1", null, CARRIER)))
                .withMessageContaining("carrierId");
    }

    @Test
    @DisplayName("accepts a driver with no carrier - a company may employ its own")
    void carrierIsOptional() {
        DriverView view = service.create(SCOPE, request("dr-own", "1", "Q-1", null, null));

        assertThat(view.carrierId()).isNull();
        assertThat(view.carrierBusinessName()).isNull();
        verify(carrierRepository, never()).findByIdAndCompanyId(any(), any());
    }

    @Test
    @DisplayName("resolves the carrier's name for display when one is attached")
    void resolvesTheCarrierName() {
        Carrier carrier = new Carrier(COMPANY, "ACME", "Acme Transport S.A.", "RUC", "20100000001",
                null, null, null, null, ACTOR);
        when(carrierRepository.findByIdAndCompanyId(CARRIER, COMPANY)).thenReturn(Optional.of(carrier));

        DriverView view = service.create(SCOPE, request("dr-acme", "1", "Q-1", null, CARRIER));

        assertThat(view.carrierBusinessName()).isEqualTo("Acme Transport S.A.");
    }

    @Test
    @DisplayName("404s a driver of another company - every lookup is scoped, never filtered afterwards")
    void crossTenantLookupIsNotFound() {
        UUID id = UUID.randomUUID();
        when(driverRepository.findByIdAndCompanyId(id, COMPANY)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.get(SCOPE, id));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.deactivate(SCOPE, id));
    }

    @Test
    @DisplayName("deactivation flips the flag and keeps everything else, so the record stays readable")
    void deactivationKeepsTheRecord() {
        UUID id = UUID.randomUUID();
        Driver driver = new Driver(COMPANY, "DR-ANA", "Ana", "Quispe", "DNI", "12345678", null, "Q-987654",
                null, null, null, ACTOR);
        when(driverRepository.findByIdAndCompanyId(id, COMPANY)).thenReturn(Optional.of(driver));

        DriverView view = service.deactivate(SCOPE, id);

        assertThat(view.active()).isFalse();
        assertThat(view.fullName()).isEqualTo("Quispe, Ana");
        assertThat(view.licenseNumber()).isEqualTo("Q-987654");
    }
}
