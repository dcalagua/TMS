package com.ebim.tms.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.fleet.domain.DriverShift;
import com.ebim.tms.fleet.domain.UnavailabilityReason;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActor;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.ResourceBlock;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Vehicle and driver availability against real PostgreSQL (migration V42).
 *
 * <p>Two things are actually under test here. The first is
 * {@link Concurrency#twoSimultaneousBlocksOneWins}: overlapping downtime on one truck must be
 * impossible even when two planners record it at the same instant, because a second overlapping
 * "in maintenance" row is what makes a downtime report double-count.
 *
 * <p>The second is {@link Shifts#hoursSurviveTheRoundTrip}, which is a regression guard and not a
 * feature test. JOB 08 found {@code hibernate.jdbc.time_zone: UTC} silently shifting dock opening
 * hours by each site's offset; storing minutes since local midnight is the fix, and this asserts
 * that a shift written as 06:00 comes back as 06:00 and is stored as 360.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@ActiveProfiles("test")
class ResourceAvailabilityIntegrationTest {

    private static final OffsetDateTime MONDAY_0800 =
            OffsetDateTime.of(2026, 9, 7, 8, 0, 0, 0, ZoneOffset.UTC);

    private static String jdbcUrl;
    private static UUID companyA;
    private static UUID vehicleA;
    private static UUID driverA;

    @Autowired
    private ResourceAvailabilityService service;

    @MockitoBean
    private AuditActorProvider auditActorProvider;

    private UUID actorId;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_availability");
        seed();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seed() {
        execute("INSERT INTO tms.organization (code, name) VALUES ('AVL-ORG', 'Availability Org')");
        execute("""
                INSERT INTO tms.company (organization_id, code, name, time_zone)
                SELECT o.id, 'AVL-A', 'Availability A', 'America/Lima'
                FROM tms.organization o WHERE o.code = 'AVL-ORG'
                """);
        execute("""
                INSERT INTO tms.app_user (auth_user_id, email, full_name, active)
                VALUES ('aaaaaaaa-0000-4000-8000-000000000002', 'fleet@example.invalid', 'Fleet Clerk', true)
                """);
        companyA = idOf("SELECT id FROM tms.company WHERE code = 'AVL-A'");
        execute("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3,"
                + " max_pallets) VALUES ('" + companyA + "', 'VT-AVL', 'Rigid', 10000, 40, 20)");
        UUID vehicleType = idOf("SELECT id FROM tms.vehicle_type WHERE code = 'VT-AVL'");
        execute("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate)"
                + " VALUES ('" + companyA + "', '" + vehicleType + "', 'TR-AVL', 'AVL-001')");
        vehicleA = idOf("SELECT id FROM tms.vehicle WHERE code = 'TR-AVL'");
        execute("INSERT INTO tms.driver (company_id, code, first_name, last_name, document_type,"
                + " document_number, license_number) VALUES ('" + companyA + "', 'DR-AVL', 'Ana', 'Rios',"
                + " 'DNI', '10000001', 'L-AVL')");
        driverA = idOf("SELECT id FROM tms.driver WHERE code = 'DR-AVL'");
    }

    @BeforeEach
    void setUp() {
        actorId = idOf("SELECT id FROM tms.app_user WHERE email = 'fleet@example.invalid'");
        org.mockito.Mockito.when(auditActorProvider.requireAppUserId()).thenReturn(actorId);
        org.mockito.Mockito.when(auditActorProvider.writerAppUserId()).thenReturn(actorId);
        org.mockito.Mockito.when(auditActorProvider.current()).thenReturn(Optional.of(
                AuditActor.person(actorId, "fleet@example.invalid", companyA, UUID.randomUUID(), "corr")));
        execute("DELETE FROM tms.resource_unavailability");
        execute("DELETE FROM tms.driver_shift");
        // As the owner, not through tms_app - the audit log has no DELETE grant, deliberately, and
        // this is fixture teardown rather than anything the application can do.
        execute("DELETE FROM tms.audit_event");
    }

    private static CompanyScope scope() {
        return new CompanyScope(companyA, "AVL", "Availability A", "America/Lima", UUID.randomUUID(),
                "AVL-ORG", "Availability Org", EnumSet.allOf(Permission.class));
    }

    // --- one block per resource per instant ---------------------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("downtime cannot overlap itself")
    class NoOverlap {

        @Test
        @DisplayName("a second overlapping block on one vehicle is refused, naming the one in the way")
        void overlappingVehicleBlockIsRefused() {
            service.blockVehicle(scope(), vehicleA, UnavailabilityReason.MAINTENANCE,
                    MONDAY_0800, MONDAY_0800.plusHours(4), null);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.blockVehicle(scope(), vehicleA, UnavailabilityReason.REPAIR,
                            MONDAY_0800.plusHours(2), MONDAY_0800.plusHours(6), null))
                    .withMessageContaining("already unavailable");
        }

        /**
         * The convention {@code tstzrange}'s {@code &&} uses, asserted so it cannot drift: a block
         * starting exactly when another ends does not overlap it. A truck out of the workshop at
         * 12:00 is available at 12:00.
         */
        @Test
        @DisplayName("back-to-back blocks do not overlap")
        void adjacentBlocksAreAllowed() {
            service.blockVehicle(scope(), vehicleA, UnavailabilityReason.MAINTENANCE,
                    MONDAY_0800, MONDAY_0800.plusHours(4), null);

            assertThat(service.blockVehicle(scope(), vehicleA, UnavailabilityReason.INSPECTION,
                    MONDAY_0800.plusHours(4), MONDAY_0800.plusHours(6), null).id()).isNotNull();
        }

        /**
         * The two EXCLUDE constraints are partial and independent. A driver blocked over a window
         * says nothing about any truck, and a shared constraint would have made one resource's
         * downtime silently block another's.
         */
        @Test
        @DisplayName("a driver's block and a vehicle's do not collide")
        void differentResourcesDoNotCollide() {
            service.blockVehicle(scope(), vehicleA, UnavailabilityReason.MAINTENANCE,
                    MONDAY_0800, MONDAY_0800.plusHours(4), null);

            assertThat(service.blockDriver(scope(), driverA, UnavailabilityReason.MEDICAL,
                    MONDAY_0800, MONDAY_0800.plusHours(4), null).id()).isNotNull();
        }
    }

    // --- a reason has to describe the thing it is about ---------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("a reason has to fit the resource")
    class Reasons {

        @Test
        @DisplayName("a vehicle cannot be on holiday")
        void vehicleRejectsADriverReason() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.blockVehicle(scope(), vehicleA, UnavailabilityReason.HOLIDAY,
                            MONDAY_0800, MONDAY_0800.plusHours(4), null))
                    .withMessageContaining("does not describe a vehicle");
        }

        @Test
        @DisplayName("a driver cannot be in for repair")
        void driverRejectsAVehicleReason() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.blockDriver(scope(), driverA, UnavailabilityReason.REPAIR,
                            MONDAY_0800, MONDAY_0800.plusHours(4), null))
                    .withMessageContaining("does not describe a driver");
        }

        @Test
        @DisplayName("OTHER describes either, because an operation always has a reason nobody listed")
        void otherFitsBoth() {
            assertThat(service.blockVehicle(scope(), vehicleA, UnavailabilityReason.OTHER,
                    MONDAY_0800, MONDAY_0800.plusHours(1), "seized by customs").id()).isNotNull();
            assertThat(service.blockDriver(scope(), driverA, UnavailabilityReason.OTHER,
                    MONDAY_0800, MONDAY_0800.plusHours(1), "jury service").id()).isNotNull();
        }
    }

    // --- the question planning actually asks --------------------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("the port planning asks")
    class Port {

        @Test
        @DisplayName("a blocked vehicle is reported with its reason and when it frees up")
        void reportsTheBlock() {
            service.blockVehicle(scope(), vehicleA, UnavailabilityReason.MAINTENANCE,
                    MONDAY_0800, MONDAY_0800.plusHours(4), null);

            Optional<ResourceBlock> block =
                    service.findBlock(companyA, vehicleA, null, MONDAY_0800.plusHours(1));

            assertThat(block).isPresent();
            assertThat(block.get().resource()).isEqualTo("vehicle");
            assertThat(block.get().reason()).isEqualTo("MAINTENANCE");
        }

        /** Half-open, matching the constraint: out of the workshop at 12:00 means available at 12:00. */
        @Test
        @DisplayName("a block does not cover the instant it ends")
        void endIsExclusive() {
            service.blockVehicle(scope(), vehicleA, UnavailabilityReason.MAINTENANCE,
                    MONDAY_0800, MONDAY_0800.plusHours(4), null);

            assertThat(service.findBlock(companyA, vehicleA, null, MONDAY_0800.plusHours(4))).isEmpty();
        }

        @Test
        @DisplayName("a driver's block stops a shipment even when the truck is free")
        void eitherResourceBlocks() {
            service.blockDriver(scope(), driverA, UnavailabilityReason.ABSENCE,
                    MONDAY_0800, MONDAY_0800.plusHours(8), null);

            Optional<ResourceBlock> block =
                    service.findBlock(companyA, vehicleA, driverA, MONDAY_0800.plusHours(1));

            assertThat(block).isPresent();
            assertThat(block.get().resource()).isEqualTo("driver");
        }

        @Test
        @DisplayName("nothing named, nothing blocked - and no query")
        void neitherResourceIsNotABlock() {
            assertThat(service.findBlock(companyA, null, null, MONDAY_0800)).isEmpty();
        }

        /**
         * The tenant predicate is in the query and not applied afterwards. Another company asking
         * about this vehicle's id learns nothing, which is the whole point of every finder in this
         * codebase carrying {@code companyId}.
         */
        @Test
        @DisplayName("another company sees no block on this company's vehicle")
        void blocksAreCompanyScoped() {
            service.blockVehicle(scope(), vehicleA, UnavailabilityReason.MAINTENANCE,
                    MONDAY_0800, MONDAY_0800.plusHours(4), null);

            assertThat(service.findBlock(UUID.randomUUID(), vehicleA, null, MONDAY_0800.plusHours(1)))
                    .isEmpty();
        }
    }

    // --- releasing ----------------------------------------------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("putting a resource back")
    class Releasing {

        @Test
        @DisplayName("releasing frees the window")
        void releasingFreesTheWindow() {
            UUID block = service.blockVehicle(scope(), vehicleA, UnavailabilityReason.MAINTENANCE,
                    MONDAY_0800, MONDAY_0800.plusHours(4), null).id();

            service.releaseVehicle(scope(), vehicleA, block);

            assertThat(service.findBlock(companyA, vehicleA, null, MONDAY_0800.plusHours(1))).isEmpty();
        }

        /**
         * The authorization check, not tidiness. Vehicle downtime is guarded by
         * {@code fleet.vehicle:manage} and driver absence by {@code fleet.driver:manage} precisely
         * so the two stay apart (V26). A delete that found the block by id alone would let the
         * driver endpoint remove a truck's block, and the split would mean nothing where it counts.
         */
        @Test
        @DisplayName("the driver endpoint cannot delete a vehicle's block")
        void releaseIsScopedToItsResource() {
            UUID block = service.blockVehicle(scope(), vehicleA, UnavailabilityReason.MAINTENANCE,
                    MONDAY_0800, MONDAY_0800.plusHours(4), null).id();

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.releaseDriver(scope(), driverA, block));

            assertThat(service.findBlock(companyA, vehicleA, null, MONDAY_0800.plusHours(1))).isPresent();
        }

        @Test
        @DisplayName("both sides of the release are on the audit trail, which is all that survives the delete")
        void releaseIsAudited() {
            UUID block = service.blockVehicle(scope(), vehicleA, UnavailabilityReason.MAINTENANCE,
                    MONDAY_0800, MONDAY_0800.plusHours(4), null).id();
            service.releaseVehicle(scope(), vehicleA, block);

            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + vehicleA
                    + "' AND action IN ('RESOURCE_BLOCKED', 'RESOURCE_RELEASED')")).isEqualTo(2);
        }
    }

    // --- shifts -------------------------------------------------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("a driver's normal hours")
    class Shifts {

        /**
         * The JOB 08 regression, guarded. {@code hibernate.jdbc.time_zone: UTC} normalises temporal
         * values on write; storing minutes is what makes 06:00 mean 06:00 at the depot regardless
         * of where the server is. Both halves are asserted - what comes back, and what is stored.
         */
        @Test
        @DisplayName("06:00 comes back as 06:00, and is stored as 360")
        void hoursSurviveTheRoundTrip() {
            DriverShift saved = service.setShift(scope(), driverA, DayOfWeek.TUESDAY,
                    LocalTime.of(6, 0), LocalTime.of(18, 30));

            assertThat(saved.startsAt()).isEqualTo(LocalTime.of(6, 0));
            assertThat(saved.endsAt()).isEqualTo(LocalTime.of(18, 30));
            assertThat(count("SELECT starts_at_minutes FROM tms.driver_shift WHERE id = '" + saved.id() + "'"))
                    .isEqualTo(360);
            assertThat(count("SELECT ends_at_minutes FROM tms.driver_shift WHERE id = '" + saved.id() + "'"))
                    .isEqualTo(1110);
        }

        @Test
        @DisplayName("setting a day twice replaces it, because one driver has one Tuesday")
        void settingTwiceReplaces() {
            UUID first = service.setShift(scope(), driverA, DayOfWeek.TUESDAY,
                    LocalTime.of(6, 0), LocalTime.of(14, 0)).id();
            UUID second = service.setShift(scope(), driverA, DayOfWeek.TUESDAY,
                    LocalTime.of(7, 0), LocalTime.of(16, 0)).id();

            assertThat(second).isEqualTo(first);
            assertThat(service.listShifts(scope(), driverA)).hasSize(1);
            assertThat(service.listShifts(scope(), driverA).getFirst().startsAt()).isEqualTo(LocalTime.of(7, 0));
        }

        @Test
        @DisplayName("an overnight shift is refused: it is two shifts on two days")
        void overnightIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.setShift(scope(), driverA, DayOfWeek.FRIDAY,
                            LocalTime.of(22, 0), LocalTime.of(6, 0)))
                    .withMessageContaining("two shifts");
        }

        @Test
        @DisplayName("a shift covers its start and not its end")
        void coversIsHalfOpen() {
            DriverShift shift = service.setShift(scope(), driverA, DayOfWeek.WEDNESDAY,
                    LocalTime.of(8, 0), LocalTime.of(17, 0));

            assertThat(shift.covers(LocalTime.of(8, 0))).isTrue();
            assertThat(shift.covers(LocalTime.of(16, 59))).isTrue();
            assertThat(shift.covers(LocalTime.of(17, 0))).isFalse();
        }
    }

    // --- the race the constraint exists for ---------------------------------------------

    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    @Nested
    @DisplayName("two planners at once")
    class Concurrency {

        /**
         * What {@code ex_vehicle_unavailability_no_overlap} is for. Two threads booking one truck
         * into the workshop for the same window at the same instant both pass
         * {@code requireFree} - a check and a write are not one operation - and exactly one may
         * end up with a row.
         */
        @Test
        @DisplayName("two simultaneous blocks on one vehicle: exactly one wins")
        void twoSimultaneousBlocksOneWins() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<Boolean> first = pool.submit(() -> attempt(ready, go));
                Future<Boolean> second = pool.submit(() -> attempt(ready, go));
                ready.await();
                go.countDown();

                int won = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
                assertThat(won)
                        .as("exactly one of two simultaneous blocks on one vehicle may succeed")
                        .isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }

            assertThat(count("SELECT count(*) FROM tms.resource_unavailability WHERE vehicle_id = '"
                    + vehicleA + "'")).isEqualTo(1);
        }

        private boolean attempt(CountDownLatch ready, CountDownLatch go) {
            try {
                ready.countDown();
                go.await();
                service.blockVehicle(scope(), vehicleA, UnavailabilityReason.MAINTENANCE,
                        MONDAY_0800, MONDAY_0800.plusHours(4), null);
                return true;
            } catch (Exception refused) {
                // A conflict, a serialisation failure or a lock timeout are all refusals, which is
                // the outcome under test. None of them is a pass for the losing thread.
                return false;
            }
        }
    }

    // --- fixture plumbing ---------------------------------------------------------------

    private static UUID idOf(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return UUID.fromString(resultSet.getString(1));
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the availability fixture", failed);
        }
    }

    private static long count(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the availability fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the availability fixture", failed);
        }
    }
}
