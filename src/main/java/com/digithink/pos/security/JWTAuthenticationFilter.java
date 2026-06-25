package com.digithink.pos.security;

import java.io.IOException;
import java.util.Date;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.digithink.pos.model.UserAccount;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

@AllArgsConstructor
@Log4j2
public class JWTAuthenticationFilter extends UsernamePasswordAuthenticationFilter {

	private final AuthenticationManager authenticationManager;

	@Override
	public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
			throws AuthenticationException {
		try {
			return authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(
					request.getHeader("username"), request.getHeader("password")));
		} catch (Exception e) {
			log.error("Error in login attempt authentication: " + request.getHeader("username"), e);
			JSONObject authRep = new JSONObject();
			authRep.put("code", 403);
			authRep.put("msg", "Incorrect login or password");
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			try {
				response.getWriter().write(authRep.toString());
			} catch (IOException e1) {
				log.error("Error during authentication response writing", e1);
			}
			return null;
		}
	}

	@Override
	protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			Authentication authResult) throws IOException, ServletException {
		log.info("Successful authentication");
		UserAccount user = (UserAccount) authResult.getPrincipal();

		Date expirationDate = new Date(System.currentTimeMillis() + SecurityParams.EXPIRATION);
		String token = JWT.create().withIssuer(request.getRequestURI()).withSubject(user.getUsername())
				.withExpiresAt(expirationDate).sign(Algorithm.HMAC256(SecurityParams.SECRET));

		log.info("Expiration Time: {}", expirationDate);
		response.addHeader(SecurityParams.JWT_HEADER_NAME, SecurityParams.HEADER_PREFIX + token);

		JSONObject authRep = new JSONObject();

		authRep.put("role", user.getRole());
		authRep.put("fullName", user.getFullName());
		authRep.put("token", token);
		authRep.put("status", 200);

		// Return dynamic permissions from the user's assigned role
		if (user.getAppRole() != null) {
			authRep.put("permissions", user.getAppRole().getPermissions());
			authRep.put("appRoleId", user.getAppRole().getId());
			authRep.put("appRoleName", user.getAppRole().getName());
			authRep.put("appRoleLabel", user.getAppRole().getLabel());
			authRep.put("isPosRole", user.getAppRole().getIsPosRole());
		} else {
			authRep.put("permissions", new java.util.HashSet<>());
		}

		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(authRep.toString());
		response.getWriter().flush();
	}
}
