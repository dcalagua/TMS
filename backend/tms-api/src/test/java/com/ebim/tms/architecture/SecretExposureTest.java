package com.ebim.tms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A secret leaves this system once, at the moment it is created, and never again.
 *
 * <p>TMS holds two kinds: a webhook signing secret and an integration client secret. Both are shown
 * to a person exactly once - through {@code WebhookSubscriptionSecretView} and
 * {@code IntegrationClientSecretView} - and afterwards the ordinary views carry a four-character
 * hint and nothing else, which is enough for "the one ending 7fQ2" and useless to anybody who
 * intercepts it.
 *
 * <p><b>Why a test rather than a convention.</b> The leak this prevents is not a decision anybody
 * makes; it is a field added to a view because it was on the entity, in a change about something
 * else, reviewed by someone reading the business logic. It compiles, the screen works, and the
 * secret is now in every JSON response, every browser cache and every proxy log - and there is no
 * way to know for how long.
 */
@AnalyzeClasses(
        packages = "com.ebim.tms",
        importOptions = { ImportOption.Predefined.DoNotIncludeTests.class,
                ImportOption.Predefined.DoNotIncludeJars.class })
class SecretExposureTest {

    /**
     * The two views whose entire purpose is to hand a secret over once.
     *
     * <p>Named individually rather than matched by a suffix: "the class that shows a secret" must be
     * a decision somebody made and can be found, not a naming convention a new class can join by
     * accident.
     */
    private static final Set<String> SHOW_ONCE_VIEWS = Set.of(
            "com.ebim.tms.integration.application.WebhookSubscriptionSecretView",
            "com.ebim.tms.integration.application.IntegrationClientSecretView");

    /** Component names that carry something usable. A hint or a fingerprint carries nothing. */
    private static final Set<String> SAFE_SUFFIXES = Set.of("hint", "algorithm", "rotatedat",
            "createdat", "expiresat", "id", "code", "name", "fingerprint", "lastfour");

    @ArchTest
    void no_view_carries_a_usable_secret(JavaClasses classes) {
        List<String> exposed = new ArrayList<>();

        for (JavaClass type : classes) {
            if (!type.getSimpleName().endsWith("View") || SHOW_ONCE_VIEWS.contains(type.getName())) {
                continue;
            }
            Class<?> view = type.reflect();
            if (!view.isRecord()) {
                continue;
            }
            for (RecordComponent component : view.getRecordComponents()) {
                if (carriesASecret(component.getName())) {
                    exposed.add(type.getSimpleName() + "." + component.getName());
                }
            }
        }

        assertThat(exposed)
                .as("""
                        View components that look like a usable secret. A secret is handed over \
                        once, at creation, through a named show-once view; every other response \
                        carries a hint and nothing more. A field added here reaches every JSON \
                        response, browser cache and proxy log, for an unknown length of time.""")
                .isEmpty();
    }

    private static boolean carriesASecret(String componentName) {
        String name = componentName.toLowerCase(Locale.ROOT);
        boolean looksSecret = name.contains("secret") || name.contains("password")
                || name.contains("privatekey") || name.equals("token") || name.contains("credential");
        if (!looksSecret) {
            return false;
        }
        return SAFE_SUFFIXES.stream().noneMatch(name::endsWith);
    }
}
