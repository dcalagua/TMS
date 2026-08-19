package com.ebim.tms.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Lets a servlet filter answer with the same error document a controller would produce.
 *
 * <p>Security rejections happen before the {@code DispatcherServlet}, so they would normally
 * bypass {@link ApiExceptionHandler} and be rendered by the container's default error page -
 * a second error format for clients to handle, and an HTML one at that. Handing the exception
 * to Spring MVC's {@code handlerExceptionResolver} routes it through the very same
 * {@code @RestControllerAdvice}, so there is exactly one place that decides what an error looks
 * like and exactly one shape on the wire.
 *
 * <p>It also keeps this code free of any JSON library: serialisation stays with the configured
 * message converters instead of a hand-rolled mapper that would not share their configuration.
 */
@Component
public class ApiExceptionResponder {

    private final HandlerExceptionResolver resolver;

    public ApiExceptionResponder(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    public void respond(HttpServletRequest request, HttpServletResponse response, Exception failure)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        if (resolver.resolveException(request, response, null, failure) == null) {
            // No advice claimed it. Fail closed with a status rather than letting the request
            // continue or fall through to a container error page.
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
