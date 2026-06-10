package com.slackmsg.gateway.filter;

import com.slackmsg.gateway.config.ServiceRoutes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Reverse proxy filter — forwards /api/* requests to backend services.
 * Passes through all headers (including Authorization).
 * Static content (/index.html, etc.) and /actuator are NOT proxied.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyFilter extends OncePerRequestFilter {

    private final ServiceRoutes routes;
    private final RestTemplate restTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Only proxy /api/* paths
        if (!path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String serviceUrl = routes.resolveServiceUrl(path);
        if (serviceUrl == null) {
            response.setStatus(404);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"message\":\"Unknown route: " + path + "\"}");
            return;
        }

        String queryString = request.getQueryString();
        String targetUrl = serviceUrl + path + (queryString != null ? "?" + queryString : "");

        log.debug("Proxy: {} {} → {}", request.getMethod(), path, targetUrl);

        try {
            // Build headers
            HttpHeaders headers = new HttpHeaders();
            Collections.list(request.getHeaderNames()).forEach(name -> {
                if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                    headers.put(name, Collections.list(request.getHeaders(name)));
                }
            });

            // Read body
            byte[] body = request.getInputStream().readAllBytes();

            HttpEntity<byte[]> entity = new HttpEntity<>(body.length > 0 ? body : null, headers);
            HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase());

            ResponseEntity<byte[]> serviceResponse = restTemplate.exchange(targetUrl, method, entity, byte[].class);

            // Forward response
            response.setStatus(serviceResponse.getStatusCodeValue());
            serviceResponse.getHeaders().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("transfer-encoding")) {
                    values.forEach(v -> response.addHeader(name, v));
                }
            });
            if (serviceResponse.getBody() != null) {
                response.getOutputStream().write(serviceResponse.getBody());
            }

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            response.setStatus(e.getRawStatusCode());
            response.setContentType("application/json");
            if (e.getResponseBodyAsByteArray().length > 0) {
                response.getOutputStream().write(e.getResponseBodyAsByteArray());
            }
        } catch (Exception e) {
            log.error("Proxy error for {}: {}", targetUrl, e.getMessage());
            response.setStatus(502);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"message\":\"Service unavailable\"}");
        }
    }
}
