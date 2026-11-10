package com.digithink.zsretail.core.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Boot 4 migration, step 1.6: the chains a real start built. Each application has the same two, /ho/** first, each authorized
 * by AuthorizationFilter. A wrong order would let the main chain answer /ho/**, and the matrices alone would show it only as
 * a status to explain.
 */
public final class SecurityChains {

	private SecurityChains() {
	}

	/** authorizeRequests() built it; deprecated in Security 6 and gone in 7, so named by its name (step 1.9). */
	private static final String OLD_INTERCEPTOR = "org.springframework.security.web.access.intercept.FilterSecurityInterceptor";

	public static void assertTheTwoChains(FilterChainProxy proxy) {
		List<SecurityFilterChain> chains = proxy.getFilterChains();
		assertEquals(2, chains.size(), "the /ho/** chain and the main chain");
		assertTrue(chains.get(0).matches(request("/ho/ping")), "/ho/** comes first");
		assertFalse(chains.get(0).matches(request("/location")), "only /ho/** in the first chain");
		assertFalse(chains.get(0).matches(request("/hox")), "only /ho/** in the first chain");
		assertTrue(chains.get(1).matches(request("/location")), "the main chain takes the rest");
		for (SecurityFilterChain chain : chains) {
			List<Class<?>> filters = chain.getFilters().stream().map(Object::getClass).collect(Collectors.toList());
			assertTrue(filters.contains(AuthorizationFilter.class), "authorized by AuthorizationFilter: " + filters);
			assertFalse(filters.stream().anyMatch(type -> type.getName().equals(OLD_INTERCEPTOR)), "not by the old interceptor: " + filters);
		}
	}

	private static MockHttpServletRequest request(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setServletPath(path);
		return request;
	}
}
