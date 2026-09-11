package com.ebim.tms.appointments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ebim.tms.appointments.domain.Appointment;
import com.ebim.tms.appointments.domain.AppointmentPurpose;
import com.ebim.tms.appointments.domain.LocationResource;
import com.ebim.tms.appointments.domain.ResourceType;
import com.ebim.tms.appointments.infrastructure.AppointmentRepository;
import com.ebim.tms.appointments.infrastructure.LocationResourceRepository;
import com.ebim.tms.appointments.infrastructure.ResourceBlockedSlotRepository;
import com.ebim.tms.appointments.infrastructure.ResourceCalendarRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.AppointmentTripPort;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.LocationTimeZonePort;
import com.ebim.tms.shared.security.CompanyScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What the booking write says when the database refuses it (migration V41), with no database.
 *
 * <p>{@code ex_appointment_no_double_booking} is the guarantee and
 * {@code AppointmentServiceIntegrationTest.twoSimultaneousBookingsOneWins} is what proves it against
 * real PostgreSQL. This class tests the other half, which needs no server: whether the refusal a
 * dispatcher reads names the rule that was actually broken.
 *
 * <p>The branch used to answer "somebody booked that dock a moment ago" to <em>every</em> integrity
 * violation - a claim it had never checked. A foreign key that failed because the shipment was
 * deleted in another tab produced the same sentence, sending a dispatcher to reload a board that was
 * never the problem, and the real cause was swallowed into a message rather than logged.
 */
class AppointmentOverlapBackstopTest {

    /** PostgreSQL {@code exclusion_violation}; {@code 23503} is {@code foreign_key_violation}. */
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final OffsetDateTime NINE = OffsetDateTime.of(2026, 3, 17, 9, 0, 0, 0, ZoneOffset.UTC);

    private AppointmentRepository appointmentRepository;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(AppointmentRepository.class);
        LocationResourceRepository resources = mock(LocationResourceRepository.class);
        ResourceCalendarRepository calendars = mock(ResourceCalendarRepository.class);
        ResourceBlockedSlotRepository closures = mock(ResourceBlockedSlotRepository.class);
        DestinationLookupPort destinations = mock(DestinationLookupPort.class);
        AppointmentTripPort trips = mock(AppointmentTripPort.class);
        LocationTimeZonePort zones = mock(LocationTimeZonePort.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);

        when(resources.findByIdAndCompanyId(any(), any())).thenReturn(Optional.of(dock()));
        // No calendar is an open door (V41 section 5), and nothing else is in the way: every
        // service-level check passes, which is exactly the state the race leaves a caller in.
        when(calendars.findByCompanyIdAndResourceIdOrderByDayOfWeekAsc(any(), any())).thenReturn(List.of());
        when(closures.findOverlapping(any(), any(), any(), any())).thenReturn(List.of());
        when(appointmentRepository.findConflicting(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(actors.requireAppUserId()).thenReturn(ACTOR);

        service = new AppointmentService(appointmentRepository, resources, calendars, closures, destinations,
                trips, zones, actors, mock(AuditRecorder.class), new SimpleMeterRegistry(),
                Clock.systemUTC());
    }

    @Test
    @DisplayName("the exclusion constraint becomes a sentence about the dock board")
    void anExclusionViolationNamesTheDock() {
        when(appointmentRepository.saveAndFlush(any(Appointment.class)))
                .thenThrow(violation(EXCLUSION_VIOLATION,
                        "conflicting key value violates exclusion constraint "
                                + "\"ex_appointment_no_double_booking\""));

        assertThatThrownBy(() -> service.book(scope(), request()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DOCK-1")
                .hasMessageContaining("somebody else");
    }

    @Test
    @DisplayName("the constraint name alone is enough when the cause is not a SQLException")
    void theConstraintNameIsRecognisedWithoutASqlState() {
        // A pooling or proxy layer can hand back a cause that carries the message and not the
        // SQLSTATE. PostgreSQL quotes the constraint's own name, which is the one part of the text
        // no lc_messages setting translates.
        when(appointmentRepository.saveAndFlush(any(Appointment.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new IllegalStateException("violates exclusion constraint "
                                + "\"ex_appointment_no_double_booking\"")));

        assertThatThrownBy(() -> service.book(scope(), request()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DOCK-1");
    }

    @Test
    @DisplayName("any other violation is rethrown, not reported as a booked dock")
    void aForeignKeyViolationIsNotADoubleBooking() {
        DataIntegrityViolationException raised = violation(FOREIGN_KEY_VIOLATION,
                "insert or update on table \"appointment\" violates foreign key constraint "
                        + "\"fk_appointment_trip\"");
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenThrow(raised);

        // Rethrown unchanged so ApiExceptionHandler logs it against the correlation id and answers a
        // 409 that claims nothing about the door. Telling a dispatcher the dock was taken would be a
        // diagnosis nobody made.
        assertThatThrownBy(() -> service.book(scope(), request()))
                .isSameAs(raised);
    }

    @Test
    @DisplayName("a unique violation is not a double booking either: a dock booking has no unique key")
    void aUniqueViolationIsNotADoubleBooking() {
        DataIntegrityViolationException raised = violation("23505",
                "duplicate key value violates unique constraint \"uq_something_else\"");
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenThrow(raised);

        assertThatThrownBy(() -> service.book(scope(), request())).isSameAs(raised);
    }

    private static DataIntegrityViolationException violation(String sqlState, String message) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLException(message, sqlState));
    }

    private static LocationResource dock() {
        return new LocationResource(COMPANY, LOCATION, "DOCK-1", "Dock 1", ResourceType.DOCK, 60, ACTOR);
    }

    private static AppointmentRequest request() {
        return new AppointmentRequest(RESOURCE, AppointmentPurpose.DELIVERY, NINE, NINE.plusHours(1),
                null, null, null, null);
    }

    private static CompanyScope scope() {
        return new CompanyScope(COMPANY, "C1", "Company one", "UTC", UUID.randomUUID(), "O1",
                "Org one", Set.of());
    }
}
