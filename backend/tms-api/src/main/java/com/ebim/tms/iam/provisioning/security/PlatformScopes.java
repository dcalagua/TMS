package com.ebim.tms.iam.provisioning.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The two operations MasterAdmin may perform on TMS, as {@code scope} values and as the Spring
 * authorities they become.
 *
 * <p>Named {@code <product>:<resource>:<verb>} like every other product of the suite
 * ({@code ewm:tenant:create}, {@code esupplier:tenant:create}); {@code tms} is the product code
 * MasterAdmin registers TMS under.
 */
public final class PlatformScopes {

    public static final String TENANT_CREATE = "tms:tenant:create";
    public static final String TENANT_READ = "tms:tenant:read";

    public static final String AUTHORITY_PREFIX = "SCOPE_";
    public static final String AUTHORITY_TENANT_CREATE = AUTHORITY_PREFIX + TENANT_CREATE;
    public static final String AUTHORITY_TENANT_READ = AUTHORITY_PREFIX + TENANT_READ;

    private PlatformScopes() {}

    /**
     * The scopes a token carries, whether the issuer wrote them as a space-separated string
     * (RFC 8693) or as a JSON array. Any other shape - a number, an object - is read as NO scopes,
     * never as all of them.
     */
    public static List<String> of(Jwt token) {
        Object claim = token.getClaim("scope");
        return switch (claim) {
            case String text -> Stream.of(text.split("\\s+"))
                    .map(String::trim)
                    .filter(scope -> !scope.isEmpty())
                    .toList();
            case Collection<?> values -> values.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(scope -> !scope.isEmpty())
                    .toList();
            case null, default -> List.of();
        };
    }
}
