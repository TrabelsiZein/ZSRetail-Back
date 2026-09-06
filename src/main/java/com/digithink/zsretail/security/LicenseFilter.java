package com.digithink.zsretail.security;

import java.io.IOException;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.digithink.zsretail.model.enumeration.LicenseStatus;
import com.digithink.zsretail.service.LicenseService;

import lombok.RequiredArgsConstructor;

/**
 * Intercepts every API request and blocks it with HTTP 402 when the license is
 * MISSING or EXPIRED. Paths listed in BYPASS_PATHS are always allowed so the
 * frontend can still reach login, config, company-info, and the license upload
 * endpoint even when no valid license exists.
 */
@Component
@RequiredArgsConstructor
public class LicenseFilter extends OncePerRequestFilter {

    private final LicenseService licenseService;

    /**
     * Paths that are always allowed regardless of license status.
     * Login, public config, company info (for upload), and license API itself.
     */
    private static final List<String> BYPASS_PATHS = List.of(
            "/login",
            "/config",
            "/company-info",
            "/license",
            "/admin/license",
            "/v3/api-docs",
            "/swagger-ui",
            "/thymeleaf",
            "/franchise",
            "/sse-endpoint"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getServletPath();

        if (isBypassed(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        LicenseStatus status = licenseService.getStatus();

        if (status == LicenseStatus.MISSING || status == LicenseStatus.EXPIRED) {
            response.setStatus(402);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"licenseError\":true,\"status\":\"" + status.name() + "\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isBypassed(String path) {
        for (String bypass : BYPASS_PATHS) {
            if (path.startsWith(bypass)) return true;
        }
        return false;
    }
}
