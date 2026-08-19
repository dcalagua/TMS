package com.ebim.tms.shared.web;

import com.ebim.tms.shared.api.ApiHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the correlation identifier for the request and echoes it back.
 *
 * <p>Registered ahead of the Spring Security chain (see {@link WebConfig}) so that even a
 * request that is rejected with 401 is traceable: the identifier is in the log line, in the
 * problem document and in the response header, and support can join the three.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(ApiHeaders.CORRELATION_ID);
        if (incoming == null || incoming.isBlank()) {
            incoming = request.getHeader(ApiHeaders.REQUEST_ID);
        }

        String correlationId = CorrelationId.sanitize(incoming);
        CorrelationId.set(correlationId);
        response.setHeader(ApiHeaders.CORRELATION_ID, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Container threads are pooled: an MDC entry that outlives the request would
            // attach this identifier to an unrelated one.
            CorrelationId.clear();
        }
    }
}
