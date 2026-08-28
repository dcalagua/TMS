package com.ebim.tms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.jpa.repository.Query;

/**
 * The two ways a repository hands out another tenant's row, refused statically.
 *
 * <p><b>Why this exists.</b> "Every finder is scoped by {@code companyId} - no exceptions" is
 * written in the javadoc of most repositories here, in ADR-003 and in
 * {@code docs/security/RLS_STRATEGY.md}. Until JOB 15 it was written down and nothing checked it. A
 * finder added without a company predicate is not a style problem - it is a cross-tenant read, and
 * the kind that passes review because the method beside it looks the same.
 *
 * <p>RLS (ADR-005) catches most of these at the database. It is explicitly <em>defence in depth</em>
 * and not the authorization, and a query relying on it has already given up the property this
 * codebase keeps: that a leak is impossible rather than merely blocked.
 *
 * <h2>What is checked, and what deliberately is not</h2>
 *
 * <p>Only the two cases where an <b>attacker supplies the id</b>:
 *
 * <ol>
 *   <li>Spring Data's inherited accessors - {@code findById}, {@code existsById},
 *       {@code deleteById}, {@code getReferenceById} - which take a bare primary key and know
 *       nothing about tenancy. A service calling one has taken a UUID from a request and asked the
 *       database for whatever row it names.
 *   <li>A <em>declared</em> finder keyed by the entity's own id, which is the same hole written by
 *       hand.
 * </ol>
 *
 * <p>Finders keyed by a <em>foreign</em> id ({@code findByTripIds}, {@code countByRouteIds}) are
 * <b>not</b> flagged, and that is a considered exclusion rather than an oversight. They inherit
 * their scope from whoever resolved the parent id, which was itself a company-scoped read, and this
 * is the pattern the codebase uses everywhere - a page of trips resolves its stops in one query.
 * Flagging them would produce thirty exemptions, and an allow-list that long stops being read.
 *
 * <p>What backs that exclusion is not this test: it is the composite foreign keys
 * {@code (id, company_id)} that make a child of another tenant's parent unrepresentable in the
 * database (ADR-003), and {@code TenancyConstraintIntegrationTest}, which proves it.
 */
@AnalyzeClasses(
        packages = "com.ebim.tms",
        importOptions = { ImportOption.Predefined.DoNotIncludeTests.class,
                ImportOption.Predefined.DoNotIncludeJars.class })
class TenantScopedRepositoryTest {

    /** Spring Data's own accessors: a bare primary key, and no idea what a company is. */
    private static final Set<String> UNSCOPED_INHERITED_ACCESSORS =
            Set.of("findById", "existsById", "deleteById", "getReferenceById", "getById", "getOne");

    /**
     * The one place an inherited accessor is correct, with the reason.
     *
     * <p>The webhook dispatcher re-reads a row it has <em>already claimed</em> through
     * {@code claimDue}, which is deliberately cross-tenant and documented at length: it runs on a
     * background thread with no security context so one worker can drain every company's queue. The
     * id did not come from a request, and the tenant travels with the row.
     */
    private static final Set<String> DELIBERATE_UNSCOPED_READS = Set.of(
            "com.ebim.tms.integration.application.WebhookDeliveryQueue");

    /**
     * Declared own-id finders that are deliberately not company-scoped.
     *
     * <p>Empty, and worth keeping empty. JOB 15 removed the only entry there would have been -
     * {@code TenderWaterfallRepository#findByIdForUpdate}, an unscoped locking read of a waterfall
     * by its own id, which had no callers at all. A loaded gun with nobody holding it is still a
     * loaded gun.
     */
    private static final Set<String> DELIBERATELY_UNSCOPED_FINDERS = Set.of();

    @ArchTest
    void no_service_reads_a_row_by_bare_id(JavaClasses classes) {
        List<String> calls = new ArrayList<>();

        for (JavaClass type : classes) {
            if (DELIBERATE_UNSCOPED_READS.contains(type.getName())) {
                continue;
            }
            for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
                if (!UNSCOPED_INHERITED_ACCESSORS.contains(call.getName())) {
                    continue;
                }
                String target = call.getTargetOwner().getName();
                if (!target.startsWith("org.springframework.data") && !target.endsWith("Repository")) {
                    continue;
                }
                calls.add(type.getSimpleName() + " -> " + call.getTargetOwner().getSimpleName()
                        + "." + call.getName());
            }
        }

        assertThat(calls)
                .as("""
                        Calls to Spring Data's unscoped accessors. These take a bare primary key and \
                        know nothing about tenancy, so a UUID out of a request fetches whatever row \
                        it names. Use a findByIdAndCompanyId finder instead.""")
                .isEmpty();
    }

    @ArchTest
    void every_own_id_finder_names_the_tenant(JavaClasses classes) {
        List<String> unscoped = new ArrayList<>();

        for (JavaClass type : classes) {
            if (!type.isInterface() || !type.getSimpleName().endsWith("Repository")) {
                continue;
            }
            Class<?> repository = type.reflect();
            for (Method method : repository.getDeclaredMethods()) {
                if (method.isDefault() || method.isSynthetic() || !keyedByItsOwnId(method)) {
                    continue;
                }
                String key = repository.getSimpleName() + "#" + method.getName();
                if (DELIBERATELY_UNSCOPED_FINDERS.contains(key) || namesTheTenant(method)) {
                    continue;
                }
                unscoped.add(key);
            }
        }

        assertThat(unscoped)
                .as("""
                        Declared finders that take the entity's own id with no company predicate. \
                        This is the shape a cross-tenant read takes: the id comes from a request, \
                        and nothing checks it belongs to the caller. Add the company to the finder.""")
                .isEmpty();
    }

    /**
     * Keyed by the row itself and by <b>nothing else</b> - {@code findById(UUID)}, not
     * {@code findByIdAndFrequencyId(UUID, UUID)}.
     *
     * <p>The second key is what makes the difference, and the reasoning is the same one that
     * excludes foreign-id finders above: a finder narrowed by a parent inherits that parent's scope,
     * because the caller resolved the parent under a company predicate before asking. The composite
     * foreign keys then make a child of another tenant's parent unrepresentable at all.
     *
     * <p>{@code findByIdAndCompanyId} is caught by {@link #namesTheTenant} rather than here, so this
     * does not need to know about it.
     */
    private static boolean keyedByItsOwnId(Method method) {
        String name = method.getName();
        boolean byId = name.startsWith("findById") || name.startsWith("existsById")
                || name.startsWith("deleteById") || name.startsWith("findAllById")
                || name.startsWith("findByIdIn");
        return byId && !hasASecondKey(name);
    }

    /** Whether the finder narrows by anything beyond the id - {@code ...AndSomethingId}. */
    private static boolean hasASecondKey(String name) {
        int afterId = name.indexOf("And");
        return afterId > 0 && name.substring(afterId).endsWith("Id");
    }

    /**
     * Whether the author named the tenant: the method name mentions the company, or the query text
     * does.
     *
     * <p>Shallow on purpose. It cannot prove the predicate is <em>correct</em>, only that one is
     * there - proving correctness is what the composite foreign keys and
     * {@code TenancyConstraintIntegrationTest} do.
     */
    private static boolean namesTheTenant(Method method) {
        if (method.getName().toLowerCase(Locale.ROOT).contains("company")) {
            return true;
        }
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
            return false;
        }
        String text = query.value().toLowerCase(Locale.ROOT);
        return text.contains("companyid") || text.contains("company_id");
    }
}
