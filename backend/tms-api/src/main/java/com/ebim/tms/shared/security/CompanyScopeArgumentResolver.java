package com.ebim.tms.shared.security;

import com.ebim.tms.shared.api.ApiHeaders;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injects the validated {@link CompanyScope} into controller methods that declare it.
 *
 * <p>This is how a company-scoped endpoint declares its contract: it takes a
 * {@code CompanyScope} parameter, and the framework supplies one only if
 * {@link CompanyScopeFilter} already proved the caller's membership. There is no way for a
 * controller to construct a scope from a request value, which is what keeps the tenant out of
 * the client's control.
 *
 * <p>An endpoint that declares the parameter but receives no header fails with 400 rather
 * than defaulting to "the caller's first company": guessing a tenant is how cross-tenant
 * writes happen.
 */
public class CompanyScopeArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CompanyScope.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof TmsAuthenticationToken token)) {
            // Reached only if an endpoint asking for a company scope was left unauthenticated
            // in the filter chain, which is a wiring bug rather than a client error.
            throw new IllegalStateException(
                    "a company-scoped endpoint was reached without an authenticated TMS principal");
        }
        return token.companyScope().orElseThrow(() -> new CompanyScopeRequiredException(
                "This endpoint operates inside one company. Send the " + ApiHeaders.COMPANY_ID
                        + " header with the id of a company you are a member of."));
    }
}
