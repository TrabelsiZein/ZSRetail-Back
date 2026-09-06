package com.digithink.zsretail.security;

/**
 * Security parameters for JWT authentication
 */
public class SecurityParams {
	public static final String JWT_HEADER_NAME = "Authorization";
	public static final String SECRET = "POS_SYSTEM_SECRET_KEY_CHANGE_IN_PRODUCTION";
	public static final long EXPIRATION = 5 * 30 * 24 * 3600 * 1000L; // 5 months
	public static final String HEADER_PREFIX = "Bearer ";
}
