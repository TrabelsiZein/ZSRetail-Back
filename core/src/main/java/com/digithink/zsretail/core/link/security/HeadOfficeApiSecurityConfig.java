package com.digithink.zsretail.core.link.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.security.LicenseFilter;
import com.digithink.zsretail.core.security.SecurityConfig;
import com.digithink.zsretail.core.security.StoreApiAccess;
import com.digithink.zsretail.core.security.StoreKeyFilter;

/**
 * Head office plan, task 1.3: the security chain of /ho/**, the store-to-head-office API. It comes before the main
 * chain ({@link SecurityConfig}, order 1), which keeps every other path exactly as before.
 * <p>
 * No JWT filter here: a user token never opens /ho/**. Every request needs the authority given by
 * the head office's StoreApiKeyFilter only, so /ho/** fails closed. The chain exists on both installation types: on
 * a store, where the filter does not exist, /ho/** answers the same 401 to everyone. On a head office the license is
 * checked after the store key (402 when missing or expired). Code cleanup step 5: it names the filter through
 * {@link StoreKeyFilter} and the refusal through {@link StoreApiAccess} (core), never the head office's class.
 * <p>
 * No rule for the ERROR dispatch, unlike the main chain: it goes to /error, so it never comes through this chain.
 */
@Configuration
public class HeadOfficeApiSecurityConfig {

	/** @param storeKeyFilter absent on a store (the filter is head office only: StoreApiKeyFilter). */
	@Bean
	@Order(0)
	public SecurityFilterChain storeApiChain(HttpSecurity http, ObjectProvider<StoreKeyFilter> storeKeyFilter,
			LicenseFilter licenseFilter) throws Exception {
		http.securityMatcher(SecurityConfig.PATHS.matcher("/ho/**")).csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint((request, response, e) -> StoreApiAccess.writeRefusal(response)))
				.authorizeHttpRequests(authorize -> authorize.anyRequest()
						.hasAuthority(StoreApiAccess.STORE_AUTHORITY));

		StoreKeyFilter filter = storeKeyFilter.getIfAvailable();
		if (filter != null) {
			http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
			http.addFilterAfter(licenseFilter, filter.getClass());
		}
		return http.build();
	}

	/** The filter runs in this chain only, never as a servlet filter on every URL. */
	@Bean
	@ConditionalOnInstallation(Installation.HEAD_OFFICE)
	public FilterRegistrationBean<StoreKeyFilter> storeApiKeyFilterRegistration(StoreKeyFilter filter) {
		FilterRegistrationBean<StoreKeyFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}
}
