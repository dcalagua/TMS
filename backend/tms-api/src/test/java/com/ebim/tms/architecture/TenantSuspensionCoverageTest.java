package com.ebim.tms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.iam.provisioning.api.PlatformProvisioningPaths;
import com.ebim.tms.integration.security.IntegrationSecurityConfig;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.SecurityConfig;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The structural half of ADR-021: the tenant suspension filter is the single enforcement point, so it
 * must sit in front of EVERY business write.
 *
 * <p>TMS has no database trigger for this (every write goes React -> Spring -> PostgreSQL, ADR-001), so
 * the equivalent of "every business table carries the trigger" is "every write route is behind the
 * filter": the filter gates company-scoped requests of the user chain ({@code /api/v1}) and of the
 * partner chain ({@code /integration}). A write route under {@code /api/v1} that takes no
 * {@link CompanyScope} would not be gated, and one outside the three known prefixes would be served by
 * a chain nobody reviewed - both fail here.
 *
 * <p>Exempt, by contract: {@code /internal/platform-provisioning/**} (tenant creation, entitlements,
 * lifecycle) - the MasterAdmin channel that lifts a suspension must stay open.
 */
class TenantSuspensionCoverageTest {

    private static final String API = "/api/v1";
    private static final String BASE_PATH_PLACEHOLDER = "${tms.api.base-path}";
    private static final Set<RequestMethod> WRITES =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /** Write routes under /api/v1 allowed without a company scope. Empty: add one only with a reason. */
    private static final Set<String> UNSCOPED_API_WRITES = Set.of();

    record Route(String controller, String method, String path, boolean companyScoped) {}

    @Test
    @DisplayName("every write route is a company-scoped /api/v1 route, an /integration route, or the platform channel")
    void everyWriteIsBehindTheFilter() throws ClassNotFoundException {
        List<Route> routes = writeRoutes();
        assertThat(routes).as("the scan must find the application's write routes").hasSizeGreaterThan(50);

        List<String> uncovered = new ArrayList<>();
        for (Route route : routes) {
            if (route.path().startsWith(PlatformProvisioningPaths.BASE)) {
                continue;
            }
            if (route.path().startsWith("/integration/")) {
                // The partner chain binds the credential's company before the filter: always scoped.
                continue;
            }
            if (route.path().startsWith(API + "/")) {
                if (!route.companyScoped() && !UNSCOPED_API_WRITES.contains(route.path())) {
                    uncovered.add(route + " takes no CompanyScope, so the suspension filter would not see it");
                }
                continue;
            }
            uncovered.add(route + " is outside /api/v1, /integration and the platform channel");
        }
        assertThat(uncovered).isEmpty();
    }

    @Test
    @DisplayName("both tenant chains register the filter, and the MasterAdmin chain does not")
    void bothTenantChainsRegisterTheFilter() throws IOException {
        Path main = Path.of("src", "main", "java");
        assertThat(source(main, SecurityConfig.class)).contains("new TenantSuspensionFilter(");
        assertThat(source(main, IntegrationSecurityConfig.class)).contains("new TenantSuspensionFilter(");
        assertThat(source(main, com.ebim.tms.iam.provisioning.security.PlatformProvisioningSecurityConfig.class))
                .doesNotContain("new TenantSuspensionFilter(");
    }

    private static String source(Path root, Class<?> type) throws IOException {
        return Files.readString(root.resolve(type.getName().replace('.', '/') + ".java"));
    }

    private static List<Route> writeRoutes() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Route> routes = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.ebim.tms")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            if (controller.getEnclosingClass() != null) {
                continue; // test-only controllers nested in test classes
            }
            String base = firstPath(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null || Arrays.stream(mapping.method()).noneMatch(WRITES::contains)) {
                    continue;
                }
                String path = (base + firstPath(mapping)).replace(BASE_PATH_PLACEHOLDER, API);
                boolean scoped = Arrays.stream(method.getParameterTypes()).anyMatch(CompanyScope.class::equals);
                routes.add(new Route(controller.getSimpleName(), method.getName(), path, scoped));
            }
        }
        return routes;
    }

    private static String firstPath(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return "";
        }
        return mapping.path()[0];
    }
}
