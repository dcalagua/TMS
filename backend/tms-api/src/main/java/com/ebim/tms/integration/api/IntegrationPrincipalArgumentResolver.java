package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.security.IntegrationAuthenticationToken;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injects the authenticated {@link IntegrationPrincipal} into integration controller methods.
 *
 * <p>The machine counterpart of {@code CompanyScopeArgumentResolver}, and it exists for the same
 * reason: a controller must not be able to construct its own tenant. The only way to obtain a
 * principal here is for {@code IntegrationAuthenticationFilter} to have verified a credential
 * first, so an endpoint that declares this parameter is company-scoped by construction.
 */
public class IntegrationPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return IntegrationPrincipal.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof IntegrationAuthenticationToken token)) {
            // Only reachable if an integration endpoint were mapped outside the integration
            // security chain, which is a wiring bug rather than a client error.
            throw new IllegalStateException(
                    "an integration endpoint was reached without an authenticated integration credential");
        }
        return token.getPrincipal();
    }
}
