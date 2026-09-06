package com.digithink.zsretail.security;

import java.security.Principal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@Order(1)
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends WebSecurityConfigurerAdapter {

	@Autowired
	private UserDetailsServiceImpl userDetailsService;

	@Autowired
	private BCryptPasswordEncoder bCryptPasswordEncoder;

	/** Null when franchise.admin=false (ConditionalOnProperty prevents bean creation). */
	@Autowired(required = false)
	private FranchiseApiKeyFilter franchiseApiKeyFilter;

	@Autowired
	private LicenseFilter licenseFilter;

	@Autowired
	@Override
	protected void configure(AuthenticationManagerBuilder auth) throws Exception {
		auth.userDetailsService(userDetailsService).passwordEncoder(bCryptPasswordEncoder);
	}

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		http.csrf().disable().headers().frameOptions().sameOrigin().and().sessionManagement()
				.sessionCreationPolicy(SessionCreationPolicy.STATELESS).and().authorizeRequests()
				.antMatchers("/sse-endpoint/**", "/login/**", "/config", "/company-info",
						"/license/**",
						"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
						"/thymeleaf/**",
						// Franchise sync API: authenticated by X-Franchise-Api-Key header via FranchiseApiKeyFilter,
						// not by JWT. Only active endpoints when franchise.admin=true (ConditionalOnProperty).
						"/franchise/**",
						// Image serving is public (browser loads directly, no auth header)
						"/item-image/**")
				.permitAll().anyRequest().authenticated().and()
				.addFilter(new JWTAuthenticationFilter(authenticationManagerBean()))
				.addFilterBefore(new JWTAuthorizationFilter(), UsernamePasswordAuthenticationFilter.class);

		// License filter runs after JWT auth so we have the authenticated user context when needed
		http.addFilterAfter(licenseFilter, UsernamePasswordAuthenticationFilter.class);

		// Register the franchise API key filter before JWT processing (only when franchise.admin=true)
		if (franchiseApiKeyFilter != null) {
			http.addFilterBefore(franchiseApiKeyFilter, UsernamePasswordAuthenticationFilter.class);
		}
	}

	@Bean
	@Override
	public AuthenticationManager authenticationManagerBean() throws Exception {
		return super.authenticationManagerBean();
	}

	@Bean
	public Principal getPrincipal() {
		return SecurityContextHolder.getContext().getAuthentication();
	}
}
