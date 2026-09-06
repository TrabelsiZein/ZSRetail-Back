package com.digithink.zsretail.security;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;

import lombok.extern.log4j.Log4j2;

/**
 * JWT Authorization Filter - validates JWT tokens on each request
 */
@Log4j2
public class JWTAuthorizationFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		// CORS headers
		response.addHeader("Access-Control-Allow-Origin", "*");
		response.addHeader("Access-Control-Allow-Headers",
				"Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers, Authorization, username, password");
		response.addHeader("Access-Control-Expose-Headers",
				"Access-Control-Allow-Origin, Access-Control-Allow-Credentials, Authorization");
		response.addHeader("Access-Control-Allow-Methods", "PUT, GET, POST, DELETE, PATCH, OPTIONS");

		// Handle preflight requests
		if (request.getMethod().equals("OPTIONS")) {
			response.setStatus(HttpServletResponse.SC_OK);
			return;
		}

		// Get JWT token from header
		String jwtToken = request.getHeader(SecurityParams.JWT_HEADER_NAME);
		if (jwtToken == null || !jwtToken.startsWith(SecurityParams.HEADER_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}

		// Verify JWT token
		try {
			JWTVerifier verifier = JWT.require(Algorithm.HMAC256(SecurityParams.SECRET)).build();
			String jwt = jwtToken.substring(SecurityParams.HEADER_PREFIX.length());
			DecodedJWT decodedJWT = verifier.verify(jwt);
			String username = decodedJWT.getSubject();

			if (username != null) {
				UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
						username, null, null);
				SecurityContextHolder.getContext().setAuthentication(authenticationToken);
			}

		} catch (Exception e) {
			log.error("Token verification failed: {}", e.getMessage());
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			return;
		}

		filterChain.doFilter(request, response);
	}
}
