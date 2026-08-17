package com.lumi.wallet.common;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Carries the correlation id that every error response quotes (HELP.md section 45) and that the
 * event envelope propagates (section 25).
 *
 * <p>Held in a thread local rather than passed as a parameter, so that services raising a
 * {@link WalletException} deep in a call chain do not each have to thread it through.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationId() {
    }

    public static String getOrGenerate() {
        String current = CURRENT.get();
        return current != null ? current : "CORR-" + UUID.randomUUID();
    }

    public static void set(String correlationId) {
        CURRENT.set(correlationId);
        MDC.put(MDC_KEY, correlationId);
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    /**
     * Accepts a caller supplied correlation id, or mints one, and echoes it back on the response.
     */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class Filter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String incoming = request.getHeader(HEADER);
            String correlationId = (incoming == null || incoming.isBlank())
                    ? "CORR-" + UUID.randomUUID()
                    : incoming;
            set(correlationId);
            response.setHeader(HEADER, correlationId);
            try {
                chain.doFilter(request, response);
            } finally {
                clear();
            }
        }
    }
}
