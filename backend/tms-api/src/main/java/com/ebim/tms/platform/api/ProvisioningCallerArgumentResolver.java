package com.ebim.tms.platform.api;

import com.ebim.tms.platform.application.ProvisioningCaller;
import com.ebim.tms.platform.security.PlatformAuthentication;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Hands a control-plane handler the caller behind the token, the way
 * {@code IntegrationPrincipalArgumentResolver} does for a partner credential.
 *
 * <p>Worth the class rather than reading {@code Authentication} in the controller. The signature
 * then says what kind of caller this endpoint has - which is the same thing
 * {@code EndpointContractTest} reads to tell a machine handler from a company-scoped one, and a
 * controller that takes {@code Authentication} tells it nothing.
 */
public class ProvisioningCallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ProvisioningCaller.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof PlatformAuthentication platform)) {
            // Unreachable through the platform chain, whose only authenticator produces one. It
            // stays because "only reachable from that chain" is a property of configuration.
            throw new IllegalStateException(
                    "a control-plane endpoint was reached without an authenticated control-plane token");
        }
        return platform.getPrincipal();
    }
}
