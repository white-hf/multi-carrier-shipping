package com.enterprise.shipping.shared.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;
import com.enterprise.shipping.shared.identity.TimeOrderedIds;

/**
 * Intercepts incoming API requests to inject unique diagnostic request IDs, 
 * disable client caching, and protect local endpoints against unauthorized Cross-Origin writes.
 */
@Component
@Profile("workflow")
public class WorkflowRequestFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        
        String id = TimeOrderedIds.next();
        response.setHeader("X-Request-Id", id);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");

        try (var ignored = MDC.putCloseable("requestId", id)) {
            String origin = request.getHeader("Origin");
            
            // Reject non-safe CORS writes that do not match the backend server origin
            if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod()) 
                    && origin != null 
                    && !sameOrigin(origin, request)) {
                
                response.setStatus(403);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"ORIGIN_NOT_ALLOWED\",\"requestId\":\"" + id + "\"}");
                return;
            }
            
            chain.doFilter(request, response);
        }
    }

    private boolean sameOrigin(String origin, HttpServletRequest request) {
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() == -1 
                    ? ("https".equals(uri.getScheme()) ? 443 : 80) 
                    : uri.getPort();
            
            return request.getScheme().equals(uri.getScheme()) 
                    && request.getServerName().equalsIgnoreCase(uri.getHost()) 
                    && request.getServerPort() == port;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}