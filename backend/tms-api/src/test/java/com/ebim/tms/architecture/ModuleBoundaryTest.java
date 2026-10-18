package com.ebim.tms.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.List;

/**
 * Keeps the modular monolith modular.
 *
 * <p>These rules exist from the first commit on purpose: it is cheap to keep modules
 * separable now and expensive to untangle them after Orders and Planning are written.
 */
@AnalyzeClasses(packages = ModuleBoundaryTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    static final String ROOT = "com.ebim.tms";

    private static final List<String> BUSINESS_MODULES =
            List.of("iam", "masterdata", "fleet", "orders", "planning", "rates", "audit", "integration", "tracking",
                    // Own-fleet cost modelling (V48, JOB 22). Listed the day it was created:
                    // a new module missing from here makes this whole rule pass vacuously over it.
                    "costing",
                    // Owns tms.travel_estimate (V38). A platform capability rather than a business
                    // domain, but listed here for the same reason notification is: it is reached
                    // through com.ebim.tms.shared.reference.RoutingPort by every module that needs
                    // a distance, and leaving it unconstrained would let planning reach into its
                    // cache instead of asking the port.
                    "routing",
                    // Owns tms.appointment and the dock master data (V41). A business module like
                    // any other: it asks planning about shipments and masterdata about places
                    // through ports, and reads neither's tables.
                    "appointments",
                    // Owns tms.platform_tenant and tms.platform_provisioning (V51): the control-plane
                    // API EBIM MasterAdmin calls. Listed the day it was created, for the reason
                    // costing was - a module missing from here is a module this rule silently skips.
                    // It creates identity rows through TenantOnboardingPort rather than reaching into
                    // iam, which is precisely what these rules are here to keep true.
                    "platform",
                    // Owns tms.notification (V32). Listed here, and not left as an unconstrained
                    // package, precisely because it is the module every other one has a reason to
                    // call: an alert is a by-product of half the writes in the product. The rule
                    // forces those calls through com.ebim.tms.shared.notification.
                    // NotificationPublisher, the same way audit is reached through AuditRecorder.
                    "notification",
                    // Owns freight settlement (V46, V64) and, from phase 55, accruals and payables
                    // (ADR-023 section 0). Listed before any of that is written so the new code is
                    // held to "talk through shared": it reads trips, costs and carriers through
                    // TripSettlementLookupPort, TripCostLookupPort and CarrierLookupPort, and the
                    // KPI report reads it back through SettlementKpiPort.
                    "settlement");

    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            SlicesRuleDefinition.slices()
                    .matching(ROOT + ".(*)..")
                    .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule shared_must_not_depend_on_business_modules =
            noClasses()
                    .that().resideInAPackage(ROOT + ".shared..")
                    .should().dependOnClassesThat().resideInAnyPackage(businessPackages())
                    .because("shared is infrastructure for the modules, never the other way around")
                    .allowEmptyShould(true);

    @ArchTest
    void business_modules_must_not_depend_on_each_other(JavaClasses classes) {
        for (String module : BUSINESS_MODULES) {
            String[] others = BUSINESS_MODULES.stream()
                    .filter(other -> !other.equals(module))
                    .map(other -> ROOT + "." + other + "..")
                    .toArray(String[]::new);

            noClasses()
                    .that().resideInAPackage(ROOT + "." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(others)
                    .because("modules integrate through " + ROOT + ".shared or an explicit API, "
                            + "so that a module stays extractable later")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    /**
     * A spreadsheet library is a file-format detail of the import feature, not a building block
     * the rest of the product may reach for.
     *
     * <p>Apache POI arrived for the bulk order import's template and .xlsx reader. Left
     * unconstrained it spreads: the next report, the next export, and eventually the domain holds
     * an {@code XSSFWorkbook}. Confining it to the order import package and to
     * {@code shared.imports} - the reusable grid reader/template writer every other module's
     * import (Locations, Carriers, Vehicle Types, Vehicles) builds on instead of touching POI
     * itself - is what keeps swapping the library, or dropping .xlsx support, a change to those
     * two places alone.
     */
    @ArchTest
    static final ArchRule spreadsheet_library_stays_inside_the_import =
            noClasses()
                    .that().resideOutsideOfPackages(ROOT + ".orders.application.imports..", ROOT + ".shared.imports..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.apache.poi..")
                    .because("Apache POI is a file-format detail of the import features, not an "
                            + "architectural building block")
                    .allowEmptyShould(true);

    private static String[] businessPackages() {
        return BUSINESS_MODULES.stream().map(module -> ROOT + "." + module + "..").toArray(String[]::new);
    }
}
