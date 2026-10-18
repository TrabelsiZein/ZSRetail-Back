package com.digithink.zsretail.core.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.model.enumeration.Role;
import com.digithink.zsretail.core.repository.BadgeScanLogRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;

import lombok.extern.log4j.Log4j2;

/**
 * BadgeService - handles all badge-related business logic
 */
@Service
@Log4j2
public class BadgeService {

	/** Step S (3.1.0): the failure reason of a scan of a deactivated user's badge. */
	public static final String BADGE_OWNER_INACTIVE = "BADGE_OWNER_INACTIVE";

	/** Step S (3.1.0): the random part of a generated badge code (was java.util.Random, predictable). */
	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	private UserAccountRepository userAccountRepository;

	@Autowired
	private BadgeScanLogRepository badgeScanLogRepository;

	/**
	 * Find user by badge code
	 * Validates expiration and revocation
	 */
	public Optional<UserAccount> findUserByBadgeCode(String badgeCode) {
		if (badgeCode == null || badgeCode.trim().isEmpty()) {
			return Optional.empty();
		}

		Optional<UserAccount> userOpt = userAccountRepository.findByBadgeCode(badgeCode.trim());
		if (!userOpt.isPresent()) {
			return Optional.empty();
		}

		UserAccount user = userOpt.get();
		
		// Check if badge is revoked
		if (isBadgeRevoked(user)) {
			log.warn("Badge scan attempted for revoked badge: {}", badgeCode);
			return Optional.empty();
		}

		// Check if badge is expired
		if (isBadgeExpired(user)) {
			log.warn("Badge scan attempted for expired badge: {}", badgeCode);
			return Optional.empty();
		}

		// Step S (3.1.0): the badge of a deactivated user opens nothing (the close with a cash difference included)
		if (isBadgeOwnerInactive(user)) {
			log.warn("Badge of an inactive user refused: {}", user.getUsername());
			return Optional.empty();
		}

		return Optional.of(user);
	}

	/**
	 * Check if badge has permission
	 */
	public boolean checkBadgePermission(String badgeCode, BadgePermission permission) {
		Optional<UserAccount> userOpt = findUserByBadgeCode(badgeCode);
		if (!userOpt.isPresent()) {
			return false;
		}

		UserAccount user = userOpt.get();
		return hasPermission(user, permission);
	}

	/**
	 * Check if user has specific permission
	 */
	public boolean hasPermission(UserAccount user, BadgePermission permission) {
		if (user == null || user.getBadgePermissions() == null || user.getBadgePermissions().trim().isEmpty()) {
			return false;
		}

		Set<BadgePermission> permissions = parseBadgePermissions(user.getBadgePermissions());
		return permissions.contains(permission);
	}

	/**
	 * Scan badge and validate permission
	 * Returns scan result with validation
	 */
	@Transactional
	public BadgeScanResult scanBadge(String badgeCode, BadgePermission permission, UserAccount scannedBy, Long sessionId) {
		BadgeScanResult result = new BadgeScanResult();
		result.setBadgeCode(badgeCode);
		result.setPermission(permission);

		// Find user by badge code
		Optional<UserAccount> userOpt = userAccountRepository.findByBadgeCode(badgeCode);
		if (!userOpt.isPresent()) {
			result.setSuccess(false);
			result.setFailureReason("BADGE_NOT_EXISTS");
			logBadgeScan(result, scannedBy, sessionId);
			return result;
		}

		UserAccount user = userOpt.get();
		result.setScannedUser(user);

		// Check if badge is revoked
		if (isBadgeRevoked(user)) {
			result.setSuccess(false);
			result.setFailureReason("BADGE_REVOKED");
			logBadgeScan(result, scannedBy, sessionId);
			return result;
		}

		// Check if badge is expired
		if (isBadgeExpired(user)) {
			result.setSuccess(false);
			result.setFailureReason("BADGE_EXPIRED");
			logBadgeScan(result, scannedBy, sessionId);
			return result;
		}

		// Step S (3.1.0): the owner's account is deactivated
		if (isBadgeOwnerInactive(user)) {
			result.setSuccess(false);
			result.setFailureReason(BADGE_OWNER_INACTIVE);
			logBadgeScan(result, scannedBy, sessionId);
			return result;
		}

		// Check permission
		if (!hasPermission(user, permission)) {
			result.setSuccess(false);
			result.setFailureReason("BADGE_NO_ACCESS");
			logBadgeScan(result, scannedBy, sessionId);
			return result;
		}

		// Success
		result.setSuccess(true);
		result.setHasPermission(true);
		logBadgeScan(result, scannedBy, sessionId);

		return result;
	}

	/**
	 * Generate unique badge code
	 */
	public String generateBadgeCode(UserAccount user) {
		String prefix = user.getUsername().toUpperCase().replaceAll("[^A-Z0-9]", "");
		if (prefix.length() > 10) {
			prefix = prefix.substring(0, 10);
		}
		
		String badgeCode;
		boolean exists = true;
		int attempts = 0;
		
		do {
			int randomNum = 1000 + RANDOM.nextInt(9000); // 4-digit random number (step S: SecureRandom)
			badgeCode = prefix + "-" + randomNum;
			exists = userAccountRepository.findByBadgeCode(badgeCode).isPresent();
			attempts++;
		} while (exists && attempts < 100);
		
		if (exists) {
			// Fallback: use timestamp
			badgeCode = prefix + "-" + System.currentTimeMillis();
		}
		
		return badgeCode;
	}

	/**
	 * Validate badge code format
	 */
	public boolean validateBadgeCode(String badgeCode) {
		if (badgeCode == null || badgeCode.trim().isEmpty()) {
			return false;
		}
		
		String trimmed = badgeCode.trim();
		// Minimum 6 characters, maximum 50 characters, alphanumeric and hyphens
		return trimmed.length() >= 6 && trimmed.length() <= 50 && trimmed.matches("^[A-Za-z0-9\\-]+$");
	}

	/**
	 * Revoke badge
	 */
	@Transactional
	public void revokeBadge(UserAccount user, UserAccount revokedBy, String reason) {
		if (user == null) {
			throw new IllegalArgumentException("User cannot be null");
		}

		user.setBadgeRevoked(true);
		user.setBadgeRevokedAt(LocalDateTime.now());
		user.setBadgeRevokedBy(revokedBy);
		user.setBadgeRevokeReason(reason);
		
		userAccountRepository.save(user);
		log.info("Badge revoked for user: {} by: {} reason: {}", user.getUsername(), 
				revokedBy != null ? revokedBy.getUsername() : "System", reason);
	}

	/**
	 * Reactivate revoked badge
	 */
	@Transactional
	public void reactivateBadge(UserAccount user) {
		if (user == null) {
			throw new IllegalArgumentException("User cannot be null");
		}

		user.setBadgeRevoked(false);
		user.setBadgeRevokedAt(null);
		user.setBadgeRevokedBy(null);
		user.setBadgeRevokeReason(null);
		
		userAccountRepository.save(user);
		log.info("Badge reactivated for user: {}", user.getUsername());
	}

	/**
	 * Check if badge is expired
	 */
	public boolean isBadgeExpired(UserAccount user) {
		if (user == null || user.getBadgeExpirationDate() == null) {
			return false; // No expiration date means never expires
		}
		return LocalDateTime.now().isAfter(user.getBadgeExpirationDate());
	}

	/**
	 * Step S (3.1.0): whether the badge's owner is a deactivated user (active false; null is read as active, as the users
	 * list reads it). Such a badge is refused, BADGE_OWNER_INACTIVE at a scan.
	 */
	public boolean isBadgeOwnerInactive(UserAccount user) {
		return user != null && Boolean.FALSE.equals(user.getActive());
	}

	/**
	 * Check if badge is revoked
	 */
	public boolean isBadgeRevoked(UserAccount user) {
		if (user == null) {
			return false;
		}
		return Boolean.TRUE.equals(user.getBadgeRevoked());
	}

	/**
	 * Parse badge permissions string to Set
	 */
	public Set<BadgePermission> parseBadgePermissions(String permissionsString) {
		if (permissionsString == null || permissionsString.trim().isEmpty()) {
			return new HashSet<>();
		}

		return Arrays.stream(permissionsString.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.map(s -> {
					try {
						return BadgePermission.valueOf(s);
					} catch (IllegalArgumentException e) {
						log.warn("Invalid badge permission: {}", s);
						return null;
					}
				})
				.filter(p -> p != null)
				.collect(Collectors.toSet());
	}

	/**
	 * Convert Set of BadgePermission to comma-separated string
	 */
	public String badgePermissionsToString(Set<BadgePermission> permissions) {
		if (permissions == null || permissions.isEmpty()) {
			return "";
		}
		return permissions.stream()
				.map(BadgePermission::name)
				.collect(Collectors.joining(","));
	}

	/**
	 * Get all permissions for RESPONSIBLE role (default)
	 */
	public Set<BadgePermission> getAllPermissions() {
		return new HashSet<>(Arrays.asList(BadgePermission.values()));
	}

	/**
	 * Initialize badge permissions for user based on role
	 */
	public void initializeBadgePermissionsForRole(UserAccount user) {
		if (user == null || user.getRole() == null) {
			return;
		}

		if (user.getRole() == Role.RESPONSIBLE) {
			// RESPONSIBLE gets all permissions by default
			Set<BadgePermission> allPermissions = getAllPermissions();
			user.setBadgePermissions(badgePermissionsToString(allPermissions));
		} else {
			// POS_USER gets no permissions by default
			user.setBadgePermissions("");
		}
	}

	/**
	 * Log badge scan attempt
	 */
	private void logBadgeScan(BadgeScanResult result, UserAccount scannedBy, Long sessionId) {
		try {
			BadgeScanLog log = new BadgeScanLog();
			log.setScannedBy(scannedBy);
			log.setScannedBadgeCode(result.getBadgeCode());
			log.setScannedUser(result.getScannedUser());
			log.setFunctionality(result.getPermission());
			log.setSuccess(result.isSuccess());
			log.setFailureReason(result.getFailureReason());
			log.setSessionId(sessionId);
			log.setTimestamp(LocalDateTime.now());
			// scanType is kept for backward compatibility but not used
			log.setScanType("SCAN");
			
			badgeScanLogRepository.save(log);
		} catch (Exception e) {
			log.error("Error logging badge scan: {}", e.getMessage(), e);
		}
	}

	/**
	 * BadgeScanResult - inner class to hold scan result
	 */
	public static class BadgeScanResult {
		private String badgeCode;
		private BadgePermission permission;
		private UserAccount scannedUser;
		private boolean success;
		private boolean hasPermission;
		private String failureReason;

		// Getters and setters
		public String getBadgeCode() {
			return badgeCode;
		}

		public void setBadgeCode(String badgeCode) {
			this.badgeCode = badgeCode;
		}

		public BadgePermission getPermission() {
			return permission;
		}

		public void setPermission(BadgePermission permission) {
			this.permission = permission;
		}

		public UserAccount getScannedUser() {
			return scannedUser;
		}

		public void setScannedUser(UserAccount scannedUser) {
			this.scannedUser = scannedUser;
		}

		public boolean isSuccess() {
			return success;
		}

		public void setSuccess(boolean success) {
			this.success = success;
		}

		public boolean isHasPermission() {
			return hasPermission;
		}

		public void setHasPermission(boolean hasPermission) {
			this.hasPermission = hasPermission;
		}

		public String getFailureReason() {
			return failureReason;
		}

		public void setFailureReason(String failureReason) {
			this.failureReason = failureReason;
		}
	}
}

