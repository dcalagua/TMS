package com.ebim.tms.shared.security;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Publishes the request's company to PostgreSQL and drops the connection into the non-owner
 * runtime role, so that Row Level Security - not only the repository layer - decides which
 * rows exist for this transaction.
 *
 * <p>This is the runtime half of {@code V13__tenant_rls_runtime_role_and_policies.sql}; the
 * policies there are keyed on {@code tms.current_company_id()}, which reads the
 * {@code tms.company_id} setting written here. See ADR-005.
 *
 * <h2>Why the role is switched rather than forced</h2>
 *
 * <p>The backend owns the {@code tms} schema, and an owner is exempt from RLS unless the table
 * is {@code FORCE}d. Forcing it would also subject Flyway and the test seeds to the policies,
 * which turns a control into a trap. Instead the connection stays the owner's and this class
 * calls {@code SET ROLE tms_app} for the work of one request: {@code tms_app} is not the owner,
 * so the policies apply to it in full.
 *
 * <h2>Unscoped connections deliberately stay as the owner</h2>
 *
 * <p>When there is no {@link CompanyScope} on the security context the role is <em>not</em>
 * switched. That covers three cases that must keep working and that have no tenant to be
 * scoped to:
 *
 * <ol>
 *   <li>Flyway, which runs migrations at startup on this same {@link DataSource};</li>
 *   <li>authentication itself - {@code PrincipalResolutionService} reads {@code app_user} and
 *       {@code membership} precisely in order to <em>decide</em> the company scope, so it
 *       cannot already have one;</li>
 *   <li>principal-scoped endpoints such as {@code /api/v1/me}.</li>
 * </ol>
 *
 * <p>The consequence is stated plainly: RLS constrains company-scoped request paths, which is
 * where cross-tenant reads would be a breach. It is not a second line of defense for the
 * identity path, which stays Spring Boot's responsibility exactly as ADR-003 describes.
 *
 * <h2>Pooling</h2>
 *
 * <p>Both settings are session-level, and HikariCP hands the same physical connection to the
 * next caller. The returned connection is therefore a proxy whose {@code close()} resets the
 * role and the setting before releasing it to the pool. A reset that fails marks the state as
 * unknown and closes the physical connection instead of returning a dirty one to the pool.
 */
public class TenantScopedDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantScopedDataSource.class);

    static final String RUNTIME_ROLE = "tms_app";
    static final String COMPANY_SETTING = "tms.company_id";

    public TenantScopedDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return scope(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return scope(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection scope(Connection connection) throws SQLException {
        Optional<CompanyScope> scope = currentCompanyScope();
        if (scope.isEmpty()) {
            return connection;
        }
        String companyId = scope.get().companyId().toString();
        try {
            // set_config is used rather than a literal `SET` so the value is bound as a
            // parameter. The id comes from a resolved CompanyScope and is a UUID, so it could
            // not carry SQL anyway - but a setting written from request-derived data is not
            // the place to rely on that.
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT set_config(?, ?, false)")) {
                statement.setString(1, COMPANY_SETTING);
                statement.setString(2, companyId);
                statement.execute();
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + RUNTIME_ROLE);
            }
        } catch (SQLException failure) {
            // Never hand back a connection that is neither scoped nor clean.
            connection.close();
            throw failure;
        }
        return proxy(connection);
    }

    private static Optional<CompanyScope> currentCompanyScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof TmsAuthenticationToken token) {
            return token.companyScope();
        }
        return Optional.empty();
    }

    private static Connection proxy(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                TenantScopedDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ResetOnClose(connection));
    }

    /** Restores the pooled connection to the owner role and clears the tenant setting. */
    private record ResetOnClose(Connection delegate) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                reset();
                delegate.close();
                return null;
            }
            if ("unwrap".equals(method.getName()) && args != null && args.length == 1
                    && args[0] instanceof Class<?> type && type.isInstance(delegate)) {
                return delegate;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException invocationFailure) {
                throw invocationFailure.getTargetException();
            }
        }

        private void reset() {
            try (Statement statement = delegate.createStatement()) {
                statement.execute("RESET ROLE");
                statement.execute("SELECT set_config('" + COMPANY_SETTING + "', '', false)");
            } catch (SQLException resetFailure) {
                // The pool is about to reuse this connection and we no longer know its role.
                // Returning it would silently run the next request as tms_app with a stale
                // company, so it is discarded instead. HikariCP opens a replacement.
                log.warn("Could not reset tenant scope on a pooled connection; discarding it",
                        resetFailure);
                try {
                    delegate.abort(Runnable::run);
                } catch (SQLException | RuntimeException abortFailure) {
                    log.warn("Discarding the connection also failed", abortFailure);
                }
            }
        }
    }
}
