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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.digithink.zsretail.core.config.ApplicationModeService;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/** The login: this installation's users and their BCrypt hashes. */
	@Bean
	public AuthenticationManager authenticationManager(UserDetailsServiceImpl userDetailsService,
			BCryptPasswordEncoder bCryptPasswordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(bCryptPasswordEncoder);
		return new ProviderManager(provider);
	}

	@Bean
	@Order(1)
	public SecurityFilterChain mainChain(HttpSecurity http, AuthenticationManager authenticationManager,
			LicenseFilter licenseFilter, ApplicationModeService applicationModeService) throws Exception {
		http.csrf(csrf -> csrf.disable()).headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(paths("/login/**", "/config", "/company-info", "/license/**",
								// Image serving is public (browser loads directly, no auth header)
								"/item-image/**"))
						.permitAll()
						// Every dispatch is authorized. The JWT filter runs once per request, so a dispatch of the container
						// itself (the error page, a forward) has no logged user: without this rule every error would answer
						// 403 with no body. /error is not public: called directly it asks for a logged user, as any path
						.dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
						.anyRequest().authenticated())
				.addFilter(new JWTAuthenticationFilter(authenticationManager, applicationModeService))
				.addFilterBefore(new JWTAuthorizationFilter(), UsernamePasswordAuthenticationFilter.class)
				// License filter runs after JWT auth so we have the authenticated user context when needed
				.addFilterAfter(licenseFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	/**
	 * Named path pattern matchers (Boot 4 migration, step 1.9; ant matchers until then, deprecated for removal in Security
	 * 6.5). They read the path after the context path, whatever servlets the server has: the same class on an external
	 * Tomcat, with its JSP servlet, as in the embedded Tomcat of the tests. /config does not open /config/, and /login/**
	 * opens /login (the matrix rows /config/, /CONFIG, /login/x, /hox and /ho, written on the ant matchers).
	 */
	private static RequestMatcher[] paths(String... patterns) {
		return Arrays.stream(patterns).map(PATHS::matcher).toArray(RequestMatcher[]::new);
	}

	/** The builder of the path matchers of both chains (HeadOfficeApiSecurityConfig too). */
	public static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();
}
