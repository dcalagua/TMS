package com.ebim.tms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Two mapping mistakes that are silent until they are expensive.
 *
 * <p>Neither is caught by a compiler, a migration or a code review that is looking at business
 * logic, and both corrupt data rather than failing loudly - which is the whole reason they are worth
 * a static test rather than a convention.
 */
@AnalyzeClasses(
        packages = "com.ebim.tms",
        importOptions = { ImportOption.Predefined.DoNotIncludeTests.class,
                ImportOption.Predefined.DoNotIncludeJars.class })
class PersistenceMappingTest {

    /**
     * {@code @Enumerated} without {@link EnumType#STRING} stores the enum's <b>position</b>.
     *
     * <p>Which means the day somebody inserts a value in the middle of an enum - alphabetically,
     * tidily, in a refactor that touches nothing else - every stored row silently changes meaning.
     * A shipment that was {@code CONFIRMED} becomes {@code CANCELLED}, no migration runs, no test
     * fails, and nothing in the application can tell.
     *
     * <p>The default is ORDINAL, so this is a mistake of omission: the safe mapping is the one you
     * have to remember to type. All 46 enum columns in this codebase are STRING today; this is what
     * keeps the forty-seventh from not being.
     *
     * <p>It also pairs with the database: every enum column here has a {@code CHECK ... IN (...)}
     * naming its values, and a CHECK cannot be written against a position.
     */
    @ArchTest
    void every_persisted_enum_is_stored_by_name(JavaClasses classes) {
        List<String> ordinal = new ArrayList<>();

        for (JavaClass type : classes) {
            if (!type.isAnnotatedWith(Entity.class)) {
                continue;
            }
            for (Field field : type.reflect().getDeclaredFields()) {
                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                if (enumerated != null && enumerated.value() != EnumType.STRING) {
                    ordinal.add(type.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertThat(ordinal)
                .as("""
                        Enum columns stored by ordinal. The position is what is written, so \
                        inserting a value into the middle of the enum silently rewrites the meaning \
                        of every existing row - with no migration and no failing test. Add \
                        @Enumerated(EnumType.STRING).""")
                .isEmpty();
    }

    /**
     * Money is {@link BigDecimal}, never {@code double} or {@code float}.
     *
     * <p>0.1 + 0.2 is not 0.3 in binary floating point, and a rate card that charges per kilometre
     * over a thousand shipments accumulates that error into an invoice somebody disputes. This
     * codebase already uses {@code BigDecimal} for every amount and compares with
     * {@code compareTo} rather than {@code equals} so that {@code 840.0} and {@code 840.00} are one
     * price; this stops the exception being introduced.
     *
     * <p>Deliberately narrow: it flags fields whose <em>name</em> says they are money or a rate.
     * Coordinates, percentages and durations are legitimately not {@code BigDecimal} everywhere, and
     * a rule that swept them in would be argued with rather than obeyed.
     */
    @ArchTest
    void money_is_never_a_floating_point_number(JavaClasses classes) {
        List<String> floating = new ArrayList<>();

        for (JavaClass type : classes) {
            if (!type.isAnnotatedWith(Entity.class)) {
                continue;
            }
            for (Field field : type.reflect().getDeclaredFields()) {
                if (!looksLikeMoney(field.getName())) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                if (fieldType == double.class || fieldType == Double.class
                        || fieldType == float.class || fieldType == Float.class) {
                    floating.add(type.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertThat(floating)
                .as("""
                        Money held in binary floating point. The rounding error is invisible per \
                        row and shows up in an invoice somebody disputes. Use BigDecimal.""")
                .isEmpty();
    }

    private static boolean looksLikeMoney(String fieldName) {
        String name = fieldName.toLowerCase(java.util.Locale.ROOT);
        return name.contains("amount") || name.contains("price") || name.contains("cost")
                || name.endsWith("rate") || name.contains("charge");
    }
}
