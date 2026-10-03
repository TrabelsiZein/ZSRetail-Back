package com.digithink.zsretail.headoffice.security;

import java.io.IOException;
import java.util.Collections;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.service.StoreService;
import com.digithink.zsretail.headoffice.service.StoreService.KeyCheck;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 1.3: identifies the calling store on /ho/** from the headers X-Store-Code and X-Store-Key.
 * Head office only; it runs in the /ho/** security chain only ({@link HeadOfficeApiSecurityConfig}).
 * <p>
 * Accepted: the store becomes the security principal, with the authority {@value #STORE_AUTHORITY}. Refused
 * (missing header, unknown store, wrong key, inactive store): the same 401 and body for all four; the reason is
 * logged here at WARN with the store code and the remote address. The key is never logged.
 * Controllers answer DTOs only, never the principal (it carries the key hash).
 */
@Component
@ConditionalOnHeadOffice
@Log4j2
public class StoreApiKeyFilter extends OncePerRequestFilter {

	public static final String CODE_HEADER = "X-Store-Code";
	public static final String KEY_HEADER = "X-Store-Key";
	public static final String STORE_AUTHORITY = "HO_STORE";

	static final String PATH_PREFIX = "/ho/";
	static final String REFUSAL_BODY = "{\"code\":401,\"msg\":\"Invalid store credentials.\"}";
	static final String MISSING_HEADER = "missing header";

	/** Header and path values come from the caller: logged without control characters and cut to this length. */
	private static final int MAX_LOGGED_LENGTH = 100;

	private final StoreService storeService;

	public StoreApiKeyFilter(StoreService storeService) {
		this.storeService = storeService;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getServletPath().startsWith(PATH_PREFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String code = request.getHeader(CODE_HEADER);
		String key = request.getHeader(KEY_HEADER);
		if (isBlank(code) || isBlank(key)) {
			refuse(request, response, code, MISSING_HEADER);
			return;
		}

		KeyCheck check = storeService.check(code, key);
		if (!check.isAccepted()) {
			refuse(request, response, code, check.getOutcome().getReason());
			return;
		}

		// A fresh context: whatever was there before (a user token) does not travel with the store
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new PreAuthenticatedAuthenticationToken(check.getStore(), null,
				Collections.singletonList(new SimpleGrantedAuthority(STORE_AUTHORITY))));
		SecurityContextHolder.setContext(context);
		filterChain.doFilter(request, response);
	}

	/** The single 401 of /ho/**. Also written by the chain's entry point (on a store, where this filter is absent). */
	static void writeRefusal(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write(REFUSAL_BODY);
	}

	private static void refuse(HttpServletRequest request, HttpServletResponse response, String code, String reason)
			throws IOException {
		SecurityContextHolder.clearContext();
		log.warn("Head office: /ho request refused ({}) for store '{}' from {} on {}", reason, forLog(code),
				request.getRemoteAddr(), forLog(request.getServletPath()));
		writeRefusal(response);
	}

	private static String forLog(String value) {
		if (value == null) {
			return "";
		}
		String clean = value.replaceAll("\\p{Cntrl}", "");
		return clean.length() > MAX_LOGGED_LENGTH ? clean.substring(0, MAX_LOGGED_LENGTH) + "..." : clean;
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
