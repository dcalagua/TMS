package com.ebim.tms.appointments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebim.tms.appointments.domain.AppointmentPurpose;
import com.ebim.tms.appointments.domain.AppointmentStatus;
import com.ebim.tms.appointments.domain.ResourceType;
import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.audit.AuditActor;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Dock booking against real PostgreSQL (migration V41).
 *
 * <p>The test this whole feature is judged by is {@link Concurrency#twoSimultaneousBookingsOneWins}:
 * two threads booking the same door for the same hour, at the same instant, through the real
 * service and the real constraint. Exactly one may win, and the loser must be told so in the
 * language of the dock board.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@ActiveProfiles("test")
class AppointmentServiceIntegrationTest {

    private static final OffsetDateTime MONDAY_0900 =
            OffsetDateTime.of(2026, 9, 7, 9, 0, 0, 0, ZoneOffset.UTC);

    private static String jdbcUrl;
    private static UUID companyA;
    private static UUID companyB;
    private static UUID locationA;
    private static UUID locationB;

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private LocationResourceService resourceService;

    @MockitoBean
    private AuditActorProvider auditActorProvider;

    private UUID actorId;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_appointments");
        seed();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seed() {
        execute("INSERT INTO tms.organization (code, name) VALUES ('APT-ORG', 'Appointments Org')");
        execute("""
                INSERT INTO tms.company (organization_id, code, name, time_zone)
                SELECT o.id, v.code, v.name, 'America/Lima'
                FROM tms.organization o
                JOIN (VALUES ('APT-A', 'Appointments A'), ('APT-B', 'Appointments B')) AS v(code, name) ON true
                WHERE o.code = 'APT-ORG'
                """);
        execute("""
                INSERT INTO tms.app_user (auth_user_id, email, full_name, active)
                VALUES ('aaaaaaaa-0000-4000-8000-000000000001', 'dock@example.invalid', 'Dock Planner', true)
                """);
        companyA = idOf("SELECT id FROM tms.company WHERE code = 'APT-A'");
        companyB = idOf("SELECT id FROM tms.company WHERE code = 'APT-B'");
        locationA = insertLocation(companyA, "SITE-A");
        locationB = insertLocation(companyB, "SITE-B");
    }

    private static UUID insertLocation(UUID companyId, String code) {
        execute("INSERT INTO tms.location (company_id, code, name, time_zone, address)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + code + " name', 'America/Lima', 'Av. 1')");
        UUID id = idOf("SELECT id FROM tms.location WHERE code = '" + code + "'");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + id + "', 'DESTINATION')");
        return id;
    }

    @BeforeEach
    void setUp() {
        actorId = idOf("SELECT id FROM tms.app_user WHERE email = 'dock@example.invalid'");
        org.mockito.Mockito.when(auditActorProvider.requireAppUserId()).thenReturn(actorId);
        org.mockito.Mockito.when(auditActorProvider.writerAppUserId()).thenReturn(actorId);
        org.mockito.Mockito.when(auditActorProvider.current()).thenReturn(Optional.of(
                AuditActor.person(actorId, "dock@example.invalid", companyA, UUID.randomUUID(), "corr")));
    }

    private static CompanyScope scope(UUID companyId) {
        return new CompanyScope(companyId, "APT", "Appointments", "America/Lima", UUID.randomUUID(),
                "APT-ORG", "Appointments Org", EnumSet.allOf(Permission.class));
    }

    private UUID newDock(UUID companyId, UUID locationId, String code) {
        return resourceService.create(scope(companyId),
                new LocationResourceRequest(locationId, code, code + " door", ResourceType.DOCK, 60)).id();
    }

    private AppointmentView book(UUID companyId, UUID dockId, OffsetDateTime start, OffsetDateTime end) {
        return appointmentService.book(scope(companyId), new AppointmentRequest(
                dockId, AppointmentPurpose.DELIVERY, start, end, null, null, null, null));
    }

    // --- no double booking -------------------------------------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("one vehicle per door at a time")
    class NoDoubleBooking {

        @Test
        @DisplayName("a second booking overlapping the first is refused, naming the one in the way")
        void overlapIsRefused() {
            UUID dock = newDock(companyA, locationA, "D-OVERLAP");
            book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> book(companyA, dock, MONDAY_0900.plusMinutes(30),
                            MONDAY_0900.plusMinutes(90)))
                    .withMessageContaining("already booked");
        }

        /**
         * The convention that must match {@code tstzrange}'s {@code &&}: a booking that starts
         * exactly when another ends does not overlap it. Getting this wrong loses an hour of dock
         * capacity on every door, every day.
         */
        @Test
        @DisplayName("back-to-back bookings do not overlap")
        void adjacentIsAllowed() {
            UUID dock = newDock(companyA, locationA, "D-ADJACENT");
            book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));

            AppointmentView next = book(companyA, dock, MONDAY_0900.plusHours(1), MONDAY_0900.plusHours(2));

            assertThat(next.status()).isEqualTo(AppointmentStatus.REQUESTED);
        }

        @Test
        @DisplayName("a cancelled booking releases its slot")
        void cancellingFreesTheDoor() {
            UUID dock = newDock(companyA, locationA, "D-CANCEL");
            AppointmentView first = book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));
            appointmentService.cancel(scope(companyA), first.id(), "customer moved the load");

            AppointmentView replacement = book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));

            assertThat(replacement.id()).isNotEqualTo(first.id());
        }

        @Test
        @DisplayName("a no-show releases its slot, and the record stays")
        void noShowFreesTheDoorAndKeepsTheRecord() {
            UUID dock = newDock(companyA, locationA, "D-NOSHOW");
            AppointmentView first = book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));
            appointmentService.confirm(scope(companyA), first.id());
            appointmentService.markNoShow(scope(companyA), first.id());

            book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));

            // The evidence a demurrage conversation is argued from is still there.
            assertThat(appointmentService.get(scope(companyA), first.id()).status())
                    .isEqualTo(AppointmentStatus.NO_SHOW);
        }

        @Test
        @DisplayName("two doors at one site are two queues")
        void doorsAreIndependent() {
            UUID first = newDock(companyA, locationA, "D-ONE");
            UUID second = newDock(companyA, locationA, "D-TWO");

            book(companyA, first, MONDAY_0900, MONDAY_0900.plusHours(1));
            AppointmentView other = book(companyA, second, MONDAY_0900, MONDAY_0900.plusHours(1));

            assertThat(other.resourceId()).isEqualTo(second);
        }
    }

    // --- the race ----------------------------------------------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("two dispatchers at the same instant")
    class Concurrency {

        /**
         * The case the exclusion constraint exists for. Both threads read a free door in their own
         * snapshot and both pass the service-level check; the database is the only place one of
         * them can be stopped.
         */
        @Test
        @DisplayName("two simultaneous bookings of one dock: exactly one succeeds")
        void twoSimultaneousBookingsOneWins() throws Exception {
            UUID dock = newDock(companyA, locationA, "D-RACE");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            try {
                Future<Boolean> first = pool.submit(() -> attempt(ready, go, dock));
                Future<Boolean> second = pool.submit(() -> attempt(ready, go, dock));
                ready.await();
                go.countDown();

                int won = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
                assertThat(won)
                        .as("exactly one of two simultaneous bookings of one dock may succeed")
                        .isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }

            assertThat(count("SELECT count(*) FROM tms.appointment WHERE resource_id = '" + dock + "'"
                    + " AND status NOT IN ('CANCELLED', 'NO_SHOW')")).isEqualTo(1);
        }

        private boolean attempt(CountDownLatch ready, CountDownLatch go, UUID dock) {
            try {
                ready.countDown();
                go.await();
                book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));
                return true;
            } catch (Exception refused) {
                // A conflict, a serialisation failure or a lock timeout are all refusals, which is
                // the outcome under test. None of them is a pass for the losing thread.
                return false;
            }
        }
    }

    // --- opening hours and closures -----------------------------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("when the door is open")
    class OpeningHours {

        @Test
        @DisplayName("a door with no calendar is open: a company that configured nothing said nothing")
        void noCalendarMeansOpen() {
            UUID dock = newDock(companyA, locationA, "D-NOCAL");

            assertThat(book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1))).isNotNull();
        }

        @Test
        @DisplayName("a booking outside the door's local hours is refused, naming them")
        void outsideOpeningHours() {
            UUID dock = newDock(companyA, locationA, "D-HOURS");
            // 07:00-12:00 in Lima (UTC-5) is 12:00-17:00 UTC.
            resourceService.replaceCalendar(scope(companyA), dock, new ResourceCalendarRequest(
                    List.of(new ResourceCalendarRequest.DayHours(DayOfWeek.MONDAY,
                            LocalTime.of(7, 0), LocalTime.of(12, 0)))));

            // 09:00 UTC is 04:00 in Lima - three hours before the door opens.
            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1)))
                    .withMessageContaining("is open from");
        }

        @Test
        @DisplayName("the same window inside the local hours is accepted")
        void insideOpeningHours() {
            UUID dock = newDock(companyA, locationA, "D-HOURS-OK");
            resourceService.replaceCalendar(scope(companyA), dock, new ResourceCalendarRequest(
                    List.of(new ResourceCalendarRequest.DayHours(DayOfWeek.MONDAY,
                            LocalTime.of(7, 0), LocalTime.of(12, 0)))));

            // 14:00 UTC is 09:00 in Lima.
            assertThat(book(companyA, dock, MONDAY_0900.plusHours(5), MONDAY_0900.plusHours(6))).isNotNull();
        }

        @Test
        @DisplayName("a day the door is shut is refused, naming the day")
        void closedThatDay() {
            UUID dock = newDock(companyA, locationA, "D-CLOSED-DAY");
            resourceService.replaceCalendar(scope(companyA), dock, new ResourceCalendarRequest(
                    List.of(new ResourceCalendarRequest.DayHours(DayOfWeek.TUESDAY,
                            LocalTime.of(0, 0), LocalTime.of(23, 59)))));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> book(companyA, dock, MONDAY_0900.plusHours(5), MONDAY_0900.plusHours(6)))
                    .withMessageContaining("closed on");
        }

        @Test
        @DisplayName("a closure refuses a booking and says why")
        void blockedSlot() {
            UUID dock = newDock(companyA, locationA, "D-BLOCKED");
            resourceService.block(scope(companyA), dock, MONDAY_0900.minusHours(1),
                    MONDAY_0900.plusHours(3), "Annual stocktake");

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1)))
                    .withMessageContaining("Annual stocktake");
        }

        @Test
        @DisplayName("a door out of service takes no new bookings")
        void inactiveDock() {
            UUID dock = newDock(companyA, locationA, "D-OFF");
            resourceService.deactivate(scope(companyA), dock);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1)))
                    .withMessageContaining("out of service");
        }
    }

    // --- moving and closing out ----------------------------------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("the rest of the life of a booking")
    class Lifecycle {

        @Test
        @DisplayName("a booking can be moved onto its own old slot without conflicting with itself")
        void rescheduleDoesNotConflictWithItself() {
            UUID dock = newDock(companyA, locationA, "D-MOVE");
            AppointmentView booked = book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));

            AppointmentView moved = appointmentService.reschedule(scope(companyA), booked.id(),
                    MONDAY_0900.plusMinutes(30), MONDAY_0900.plusMinutes(90));

            assertThat(moved.status()).isEqualTo(AppointmentStatus.RESCHEDULED);
            assertThat(moved.windowStart()).isEqualTo(MONDAY_0900.plusMinutes(30));
            // Where it originally stood, kept on the row.
            assertThat(moved.rescheduledFromStart()).isEqualTo(MONDAY_0900);
        }

        @Test
        @DisplayName("moving onto another booking's slot is refused")
        void rescheduleIntoAConflict() {
            UUID dock = newDock(companyA, locationA, "D-MOVE-CLASH");
            AppointmentView first = book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));
            book(companyA, dock, MONDAY_0900.plusHours(2), MONDAY_0900.plusHours(3));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> appointmentService.reschedule(scope(companyA), first.id(),
                            MONDAY_0900.plusHours(2), MONDAY_0900.plusHours(3)));
        }

        @Test
        @DisplayName("requested, confirmed, arrived, completed")
        void theHappyPath() {
            UUID dock = newDock(companyA, locationA, "D-HAPPY");
            AppointmentView booked = book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));

            assertThat(appointmentService.confirm(scope(companyA), booked.id()).status())
                    .isEqualTo(AppointmentStatus.CONFIRMED);
            assertThat(appointmentService.arrive(scope(companyA), booked.id(), MONDAY_0900).status())
                    .isEqualTo(AppointmentStatus.ARRIVED);
            AppointmentView done = appointmentService.complete(scope(companyA), booked.id(),
                    MONDAY_0900.plusMinutes(45));
            assertThat(done.status()).isEqualTo(AppointmentStatus.COMPLETED);
            assertThat(done.completedAt()).isEqualTo(MONDAY_0900.plusMinutes(45));
        }

        @Test
        @DisplayName("a vehicle that arrived cannot be marked a no-show")
        void arrivedCannotNoShow() {
            UUID dock = newDock(companyA, locationA, "D-ARRIVED");
            AppointmentView booked = book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));
            appointmentService.confirm(scope(companyA), booked.id());
            appointmentService.arrive(scope(companyA), booked.id(), MONDAY_0900);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> appointmentService.markNoShow(scope(companyA), booked.id()));
        }

        @Test
        @DisplayName("reaching the state you asked for is not an error")
        void transitionsAreIdempotent() {
            UUID dock = newDock(companyA, locationA, "D-IDEM");
            AppointmentView booked = book(companyA, dock, MONDAY_0900, MONDAY_0900.plusHours(1));
            appointmentService.confirm(scope(companyA), booked.id());

            assertThat(appointmentService.confirm(scope(companyA), booked.id()).status())
                    .isEqualTo(AppointmentStatus.CONFIRMED);
        }

        @Test
        @DisplayName("a booking cannot hold a dock for more than a day")
        void windowIsBounded() {
            UUID dock = newDock(companyA, locationA, "D-LONG");

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> book(companyA, dock, MONDAY_0900, MONDAY_0900.plusDays(2)));
        }
    }

    // --- tenancy -------------------------------------------------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("one company cannot reach another's docks")
    class Tenancy {

        @Test
        @DisplayName("company B cannot book company A's door")
        void cannotBookAnotherCompanysDock() {
            UUID dockOfA = newDock(companyA, locationA, "D-TENANT");

            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> book(companyB, dockOfA, MONDAY_0900, MONDAY_0900.plusHours(1)))
                    .withMessageContaining("not found");
        }

        @Test
        @DisplayName("company B cannot read company A's booking")
        void cannotReadAnotherCompanysAppointment() {
            UUID dockOfA = newDock(companyA, locationA, "D-TENANT-READ");
            AppointmentView booked = book(companyA, dockOfA, MONDAY_0900, MONDAY_0900.plusHours(1));

            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> appointmentService.get(scope(companyB), booked.id()))
                    .withMessageContaining("not found");
        }

        @Test
        @DisplayName("the dock board of one company shows nothing of another's")
        void boardsAreScoped() {
            UUID dockOfA = newDock(companyA, locationA, "D-BOARD");
            book(companyA, dockOfA, MONDAY_0900, MONDAY_0900.plusHours(1));

            assertThat(appointmentService.forLocation(scope(companyB), locationA,
                    MONDAY_0900.minusDays(1), MONDAY_0900.plusDays(1))).isEmpty();
        }

        /**
         * The same door code at two companies' sites is two doors. Without the per-location
         * uniqueness this would be a cross-tenant collision on a name every warehouse uses.
         */
        @Test
        @DisplayName("two companies may each have a DOCK-1")
        void codesAreScopedToTheSite() {
            UUID first = newDock(companyA, locationA, "DOCK-1");
            UUID second = newDock(companyB, locationB, "DOCK-1");

            assertThat(first).isNotEqualTo(second);
        }
    }

    // --- fixtures ------------------------------------------------------------------------

    private static UUID idOf(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return UUID.fromString(resultSet.getString(1));
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the appointment fixture", failed);
        }
    }

    private static long count(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the appointment fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the appointment fixture", failed);
        }
    }
}
