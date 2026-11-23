package com.digithink.zsretail.core.security;

import java.security.Principal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
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

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.eventlog.service.AccessEvents;

@Configuration
@Order(1)
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends WebSecurityConfigurerAdapter {

	@Autowired
	private UserDetailsServiceImpl userDetailsService;

	@Autowired
	private BCryptPasswordEncoder bCryptPasswordEncoder;

	@Autowired
	private LicenseFilter licenseFilter;

	@Autowired
	private ApplicationModeService applicationModeService;

	/** 3.4.0, event log step 3: the login events. Lazy: resolved at the first login, never a cycle at the start. */
	@Autowired
	@Lazy
	private AccessEvents accessEvents;

	@Autowired
	@Override
	protected void configure(AuthenticationManagerBuilder auth) throws Exception {
		auth.userDetailsService(userDetailsService).passwordEncoder(bCryptPasswordEncoder);
	}

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		// 3.4.0, event log step 3: no default LogoutFilter (it answered /logout with a redirect): POST /logout is
		// LogoutAPI, which writes the LOGOUT event
		http.csrf().disable().logout().disable().headers().frameOptions().sameOrigin().and().sessionManagement()
				.sessionCreationPolicy(SessionCreationPolicy.STATELESS).and().authorizeRequests()
				.antMatchers("/sse-endpoint/**", "/login/**", "/config", "/company-info",
						"/license/**",
						"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
						"/thymeleaf/**",
						// Image serving is public (browser loads directly, no auth header)
						"/item-image/**")
				.permitAll().anyRequest().authenticated().and()
				.addFilter(new JWTAuthenticationFilter(authenticationManagerBean(), applicationModeService, accessEvents))
				.addFilterBefore(new JWTAuthorizationFilter(), UsernamePasswordAuthenticationFilter.class);

		// License filter runs after JWT auth so we have the authenticated user context when needed
		http.addFilterAfter(licenseFilter, UsernamePasswordAuthenticationFilter.class);
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
