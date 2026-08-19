package com.ebim.tms.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.members;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps the layering the review flow depends on:
 *
 * <pre>
 *   UI -&gt; API client -&gt; Controller -&gt; Service/Use Case -&gt; Repository -&gt; DB
 * </pre>
 *
 * <p>The rule that matters most is the first one. A controller that reaches a repository
 * directly skips the layer where company scoping and permission checks live, and that is
 * precisely how a cross-tenant read gets written by accident. Enforcing it now - while there
 * is one company-scoped endpoint - costs nothing; enforcing it after Orders and Planning are
 * written costs a refactor.
 */
@AnalyzeClasses(packages = LayeringTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringTest {

    static final String ROOT = "com.ebim.tms";

    @ArchTest
    static final ArchRule controllers_must_not_reach_repositories =
            noClasses()
                    .that().areAnnotatedWith(RestController.class)
                    .should().dependOnClassesThat().areAnnotatedWith(Repository.class)
                    .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                    .because("a controller that queries directly skips the layer that applies "
                            + "company scoping and permission checks")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllers_must_not_use_persistence_apis =
            noClasses()
                    .that().areAnnotatedWith(RestController.class)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..", "org.springframework.jdbc..", "javax.sql..")
                    .because("persistence is the repository layer's concern, not the web layer's")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllers_live_in_api_packages =
            classes()
                    .that().areAnnotatedWith(RestController.class)
                    .should().resideInAPackage("..api..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule use_cases_live_in_application_packages =
            classes()
                    .that().areAnnotatedWith(Service.class)
                    .should().resideInAPackage("..application..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule repositories_live_in_infrastructure_packages =
            classes()
                    .that().haveSimpleNameEndingWith("Repository")
                    .should().resideInAPackage("..infrastructure..")
                    .because("keeping every query behind one package makes an audit of tenant "
                            + "scoping a matter of reading one directory")
                    .allowEmptyShould(true);

    /**
     * Deliberately checks for {@code @RestController} rather than "resides in a package named
     * {@code api}": {@code shared.api} holds cross-cutting contract types
     * ({@code PageQuery}, {@code PageResponse}, the {@code *Exception} classes) that a use
     * case is expected to depend on, same as every other module. Only the controller layer
     * itself - the thing a use case must stay independent of - is off limits.
     */
    @ArchTest
    static final ArchRule use_cases_must_not_depend_on_the_web_layer =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().areAnnotatedWith(RestController.class)
                    .because("a use case must stay callable from a job or an integration, "
                            + "not only from a controller")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule dependencies_are_injected_through_constructors =
            members()
                    .should().notBeAnnotatedWith(Autowired.class)
                    .because("field and setter injection hide dependencies and make a class "
                            + "untestable without a container; constructors do not")
                    .allowEmptyShould(true);
}
