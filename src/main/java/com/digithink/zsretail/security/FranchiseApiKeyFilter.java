package com.digithink.zsretail.security;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the X-Franchise-Api-Key header on all /franchise/** requests.
 * Only active when franchise.admin=true.
 * Returns 401 when the key is missing or does not match the configured value.
 */
@Component
@ConditionalOnProperty(name = "franchise.admin", havingValue = "true")
public class FranchiseApiKeyFilter extends OncePerRequestFilter {

	private static final String API_KEY_HEADER = "X-Franchise-Api-Key";
	private static final String FRANCHISE_PATH_PREFIX = "/franchise/";

	@Value("${franchise.api-key:}")
	private String configuredApiKey;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String path = request.getServletPath();

		if (!path.startsWith(FRANCHISE_PATH_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}

		String providedKey = request.getHeader(API_KEY_HEADER);

		if (configuredApiKey == null || configuredApiKey.isBlank()) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			response.getWriter().write("{\"error\":\"Franchise API key is not configured on the server\"}");
			return;
		}

		if (providedKey == null || !configuredApiKey.equals(providedKey)) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType("application/json");
			response.getWriter().write("{\"error\":\"Invalid or missing X-Franchise-Api-Key header\"}");
			return;
		}

		filterChain.doFilter(request, response);
	}
}
