package com.digithink.zsretail.headoffice.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.security.LicenseFilter;
import com.digithink.zsretail.security.SecurityConfig;

/**
 * Head office plan, task 1.3: the security chain of /ho/**, the store-to-head-office API. It comes before the main
 * chain ({@link SecurityConfig}, order 1), which keeps every other path exactly as before.
 * <p>
 * No JWT filter here: a user token never opens /ho/**. Every request needs the authority given by
 * {@link StoreApiKeyFilter} only, so /ho/** fails closed. The chain exists on both installation types: on a store,
 * where the filter does not exist, /ho/** answers the same 401 to everyone. On a head office the license is checked
 * after the store key (402 when missing or expired).
 */
@Configuration
@Order(0)
public class HeadOfficeApiSecurityConfig extends WebSecurityConfigurerAdapter {

	/** Null on a store (the filter is head office only). */
	@Autowired(required = false)
	private StoreApiKeyFilter storeApiKeyFilter;

	@Autowired
	private LicenseFilter licenseFilter;

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		http.antMatcher("/ho/**").csrf().disable().sessionManagement()
				.sessionCreationPolicy(SessionCreationPolicy.STATELESS).and().exceptionHandling()
				.authenticationEntryPoint((request, response, e) -> StoreApiKeyFilter.writeRefusal(response)).and()
				.authorizeRequests().anyRequest().hasAuthority(StoreApiKeyFilter.STORE_AUTHORITY);

		if (storeApiKeyFilter != null) {
			http.addFilterBefore(storeApiKeyFilter, UsernamePasswordAuthenticationFilter.class);
			http.addFilterAfter(licenseFilter, StoreApiKeyFilter.class);
		}
	}

	/** The filter runs in this chain only, never as a servlet filter on every URL. */
	@Bean
	@ConditionalOnHeadOffice
	public FilterRegistrationBean<StoreApiKeyFilter> storeApiKeyFilterRegistration(StoreApiKeyFilter filter) {
		FilterRegistrationBean<StoreApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}
}
