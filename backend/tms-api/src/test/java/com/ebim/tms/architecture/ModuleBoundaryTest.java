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
            List.of("iam", "masterdata", "fleet", "orders", "planning", "audit", "integration");

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
