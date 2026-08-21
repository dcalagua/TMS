package com.ebim.tms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.shared.security.CompanyScope;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The contract every HTTP endpoint of TMS keeps, checked over the whole controller layer rather
 * than one endpoint at a time.
 *
 * <h2>Why this is an architecture test and not a set of endpoint tests</h2>
 *
 * <p>{@code ApiSecurityTest} proves the filter chain behaves correctly, but it proves it for the
 * two endpoints it mounts. The rules below are about the ones nobody remembered to write a test
 * for - and an endpoint that quietly leaves the tenancy contract is exactly the kind that is added
 * in a hurry, works in the browser because the frontend happens to send the header, and is never
 * looked at again. That is not a hypothetical: {@code WebhookController.eventTypes} was written
 * without its {@link CompanyScope} parameter, worked in every manual test, and was found by this
 * rule rather than by using the product.
 *
 * <h2>The three-part contract</h2>
 *
 * <p>{@code CompanyContextController}'s class comment states it, and these rules enforce it:
 * a company-scoped endpoint declares a {@link CompanyScope} parameter, carries a
 * {@code @PreAuthorize}, and delegates to a use case that takes the resolved scope. The first two
 * are mechanically checkable and are checked here; the third is
 * {@code LayeringTest.controllers_must_not_reach_repositories}.
 *
 * <p>The declared parameter is not decoration. It is the only thing that makes
 * {@code CompanyScopeArgumentResolver} run, and therefore the only thing that turns a missing
 * {@code X-Company-Id} into {@code 400 company-scope-required}. Omitting it does not open a hole -
 * an unscoped token carries no authorities, so {@code @PreAuthorize} still refuses - but it turns
 * a clear client error into {@code 403 access-denied}, which sends an integrator hunting for a
 * missing permission they already hold. Fail-closed is not the same as correct.
 */
@AnalyzeClasses(packages = EndpointContractTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class EndpointContractTest {

    static final String ROOT = "com.ebim.tms";

    /** The annotations that make a method an HTTP handler. */
    private static final List<Class<? extends Annotation>> MAPPINGS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, PatchMapping.class, DeleteMapping.class);

    /**
     * The handlers that are deliberately reachable by any authenticated caller, and the sentence
     * that justifies each. Adding an entry here is a security decision; every one of them is
     * argued at length on the controller itself.
     *
     * <ul>
     *   <li>{@code SystemInfoController#info} - the one intentionally public business path, so an
     *       operator can tell "the backend is down" from "my token is rejected". Returns no tenant
     *       or user data.</li>
     *   <li>{@code MeController#me} - principal-scoped by definition: it is the call the frontend
     *       makes <em>before</em> a company has been chosen, and there is no user id parameter to
     *       substitute.</li>
     *   <li>{@code NotificationController#*} - the alert bell is a permanent control in the top
     *       bar that no role can hide, so answering 403 to it on every page load would be worse
     *       than showing an empty panel. The disclosure is protected per alert type in
     *       {@code NotificationService}, which is finer than an endpoint could be.</li>
     *   <li>{@code IntegrationIdentityController#ping} - "does my key work?", answered with facts
     *       the holder of the credential already knows. Requiring a scope for it would mean a
     *       partner debugging authentication by posting real orders.</li>
     * </ul>
     */
    private static final Set<String> UNGUARDED_BY_DESIGN = Set.of(
            "SystemInfoController#info",
            "MeController#me",
            "NotificationController#feed",
            "NotificationController#markRead",
            "NotificationController#markAllRead",
            "IntegrationIdentityController#ping");

    /**
     * Every permission-guarded user-facing handler declares the scope its permission is evaluated
     * in.
     *
     * <p>Machine-to-machine handlers are excluded because their tenant is resolved from the
     * credential rather than from a header - see {@code IntegrationAuthenticationFilter}, which
     * calls that "the strongest form of the rule ADR-003 states, because the client is never
     * asked". They are recognised by taking an {@link IntegrationPrincipal}, not by their package:
     * {@code WebhookController} lives in {@code integration.api} and is a browser endpoint.
     */
    @ArchTest
    void permission_guarded_endpoints_declare_the_company_they_are_scoped_to(JavaClasses classes) {
        List<String> offenders = new ArrayList<>();
        for (Method handler : handlersIn(classes)) {
            if (!handler.isAnnotationPresent(PreAuthorize.class) || takes(handler, IntegrationPrincipal.class)) {
                continue;
            }
            if (!takes(handler, CompanyScope.class)) {
                offenders.add(nameOf(handler));
            }
        }

        assertThat(offenders.stream().sorted().toList())
                .as("a @PreAuthorize expression is evaluated against the permissions of the selected "
                        + "company, so a handler carrying one must declare a CompanyScope parameter - "
                        + "without it X-Company-Id is documented as required but never enforced, and a "
                        + "caller who omits it is told 'access-denied' instead of 'company-scope-required'")
                .isEmpty();
    }

    /**
     * Every handler is either permission-guarded or on the list above.
     *
     * <p>{@code SecurityConfig} ends in {@code anyRequest().authenticated()}, so nothing here is
     * anonymous - but "any authenticated user of any tenant" is not an authorization decision, and
     * an endpoint that makes one by omission should have to say so in this file.
     */
    @ArchTest
    void every_endpoint_is_permission_guarded_or_justified(JavaClasses classes) {
        List<String> offenders = new ArrayList<>();
        for (Method handler : handlersIn(classes)) {
            if (!handler.isAnnotationPresent(PreAuthorize.class) && !UNGUARDED_BY_DESIGN.contains(nameOf(handler))) {
                offenders.add(nameOf(handler));
            }
        }

        assertThat(offenders.stream().sorted().toList())
                .as("an endpoint reachable by any authenticated member of any company needs a written "
                        + "reason, not an omitted annotation; add @PreAuthorize or add the handler to "
                        + "UNGUARDED_BY_DESIGN with the sentence that justifies it")
                .isEmpty();
    }

    /**
     * A handler that takes a scope takes exactly one, and never also a machine principal.
     *
     * <p>The two are different tenancy models on different security chains: a method that declared
     * both would be reachable from neither in the shape it claims, and would be a sign somebody
     * was trying to serve a partner credential from the browser API.
     */
    @ArchTest
    void no_handler_mixes_the_two_tenancy_models(JavaClasses classes) {
        List<String> offenders = new ArrayList<>();
        for (Method handler : handlersIn(classes)) {
            if (takes(handler, CompanyScope.class) && takes(handler, IntegrationPrincipal.class)) {
                offenders.add(nameOf(handler));
            }
        }

        assertThat(offenders.stream().sorted().toList())
                .as("CompanyScope is resolved from a validated header on the user chain and "
                        + "IntegrationPrincipal from a credential on the machine chain; one handler "
                        + "cannot belong to both")
                .isEmpty();
    }

    /** Every HTTP handler method of every {@code @RestController}, as plain reflection. */
    private static List<Method> handlersIn(JavaClasses classes) {
        List<Method> handlers = new ArrayList<>();
        for (JavaClass type : classes) {
            if (!type.isAnnotatedWith(RestController.class)) {
                continue;
            }
            for (JavaMethod method : type.getMethods()) {
                Method reflected = method.reflect();
                if (MAPPINGS.stream().anyMatch(reflected::isAnnotationPresent)) {
                    handlers.add(reflected);
                }
            }
        }
        return handlers;
    }

    private static boolean takes(Method handler, Class<?> parameterType) {
        return Arrays.asList(handler.getParameterTypes()).contains(parameterType);
    }

    /** {@code VehicleController#list} - short enough to read in a failure message. */
    private static String nameOf(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }
}
