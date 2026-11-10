package com.digithink.zsretail.core.security;

import java.util.Arrays;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.digithink.zsretail.core.config.ApplicationModeService;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/** The login: this installation's users and their BCrypt hashes. */
	@Bean
	public AuthenticationManager authenticationManager(UserDetailsServiceImpl userDetailsService,
			BCryptPasswordEncoder bCryptPasswordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
		provider.setUserDetailsService(userDetailsService);
		provider.setPasswordEncoder(bCryptPasswordEncoder);
		return new ProviderManager(provider);
	}

	@Bean
	@Order(1)
	public SecurityFilterChain mainChain(HttpSecurity http, AuthenticationManager authenticationManager,
			LicenseFilter licenseFilter, ApplicationModeService applicationModeService) throws Exception {
		http.csrf(csrf -> csrf.disable()).headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize.shouldFilterAllDispatcherTypes(true)
						// Before the dispatcher rule: Boot's ErrorPageSecurityFilter asks about /error with a request that
						// throws on getDispatcherType
						.requestMatchers(paths("/login/**", "/config", "/company-info", "/license/**",
								// Image serving is public (browser loads directly, no auth header)
								"/item-image/**",
								// Boot's error page: since Boot 2.6 its body is blanked for whoever may not reach this path,
								// and the ERROR dispatch has no logged user (a stateless chain), so every error would answer
								// an empty body
								"/error"))
						.permitAll()
						// The JWT filter runs once per request: a dispatch of the container itself has no logged user
						.dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
						.anyRequest().authenticated())
				.addFilter(new JWTAuthenticationFilter(authenticationManager, applicationModeService))
				.addFilterBefore(new JWTAuthorizationFilter(), UsernamePasswordAuthenticationFilter.class)
				// License filter runs after JWT auth so we have the authenticated user context when needed
				.addFilterAfter(licenseFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	/**
	 * Named ant matchers, not requestMatchers(String...): those follow Spring MVC's rules (/config/ would open with /config),
	 * and build another class on an external Tomcat, whose JSP servlet is a second servlet, than in the embedded Tomcat of
	 * the tests.
	 */
	private static RequestMatcher[] paths(String... patterns) {
		return Arrays.stream(patterns).map(AntPathRequestMatcher::antMatcher).toArray(RequestMatcher[]::new);
	}
}
