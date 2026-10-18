package com.digithink.zsretail.core.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.model.enumeration.GrantedBy;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.BadgeScanLogRepository;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;

import lombok.extern.log4j.Log4j2;

/**
 * The badge (a credential: code, expiry, revoked) and the till rights. Step C (3.1.0): a till right belongs to the
 * role (app_role_permission "till:..." keys, {@link BadgePermission#key()}), never to a user's card: the logged user
 * passes an action when his role holds it ({@link #roleHolds}); otherwise a badge is scanned, and it opens the action
 * when its owner's role holds it. Every pass is logged in badge_scan_log: a scan (grantedBy BADGE, success or the
 * refusal reason) or a pass on the role's right (grantedBy ROLE, no badge code, the owner is the logged user). Step D:
 * for an action the server checks on its request, a scan's success gives a grant (the store's TillGrantService) and its
 * pass is logged when the request uses it ({@link #logBadgePass}), not at the scan.
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

	@Autowired
	private CashierSessionRepository cashierSessionRepository;

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
	 * Step C (3.1.0): whether the user's role holds the till right of the action (app_role_permission
	 * {@link BadgePermission#key()}). False without a user, a role or an action. The one rule of the server: the logged
	 * user's own right, and what a scanned badge opens (its owner's role).
	 */
	public boolean roleHolds(UserAccount user, BadgePermission action) {
		return user != null && action != null && user.getAppRole() != null && user.getAppRole().getPermissions() != null
				&& user.getAppRole().getPermissions().contains(action.key());
	}

	/**
	 * Step C: the id of the user's open cashier session, logged with a pass (null without one, e.g. an admin page, or
	 * when it cannot be read: the log never stops a scan).
	 */
	public Long openSessionId(UserAccount user) {
		if (user == null || cashierSessionRepository == null) {
			return null;
		}
		try {
			return cashierSessionRepository.findByCashierAndStatus(user, SessionStatus.OPENED)
					.map(CashierSession::getId).orElse(null);
		} catch (RuntimeException e) {
			log.warn("Open session of {} not read for the badge log: {}", user.getUsername(), e.getMessage());
			return null;
		}
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

		// Step C (3.1.0): the badge opens the action when its owner's role holds the right
		if (!roleHolds(user, permission)) {
			result.setSuccess(false);
			result.setFailureReason("BADGE_NO_ACCESS");
			logBadgeScan(result, scannedBy, sessionId);
			return result;
		}

		// Success. Step D (3.1.0): for an action the server checks on its request, the pass is logged when the request
		// uses the scan's grant (logBadgePass), not here: a scan whose action never happens leaves no pass
		result.setSuccess(true);
		result.setHasPermission(true);
		if (!permission.checkedOnRequest()) {
			logBadgeScan(result, scannedBy, sessionId);
		}

		return result;
	}

	/**
	 * Step C (3.1.0): logs a pass on the logged user's own role right (grantedBy ROLE): no badge code, the owner is the
	 * user himself, his open session. The caller checked {@link #roleHolds} first.
	 */
	public void logRolePass(UserAccount actor, BadgePermission action) {
		logRolePass(actor, action, openSessionId(actor));
	}

	/** Step D: the same, in the session of the request that does the action. */
	public void logRolePass(UserAccount actor, BadgePermission action, Long sessionId) {
		logPass(actor, actor, null, action, sessionId, GrantedBy.ROLE, "ROLE");
	}

	/**
	 * Step D (3.1.0): logs a pass on a badge's grant, when the request doing the action uses it (grantedBy BADGE): the
	 * user who scanned, the badge's owner and code, the request's session.
	 */
	public void logBadgePass(UserAccount scannedBy, UserAccount owner, String badgeCode, BadgePermission action,
			Long sessionId) {
		logPass(scannedBy, owner, badgeCode, action, sessionId, GrantedBy.BADGE, "SCAN");
	}

	private void logPass(UserAccount actor, UserAccount owner, String badgeCode, BadgePermission action, Long sessionId,
			GrantedBy grantedBy, String scanType) {
		try {
			BadgeScanLog row = new BadgeScanLog();
			row.setScannedBy(actor);
			row.setScannedUser(owner);
			row.setScannedBadgeCode(badgeCode);
			row.setFunctionality(action);
			row.setSuccess(true);
			row.setGrantedBy(grantedBy);
			row.setSessionId(sessionId);
			row.setTimestamp(LocalDateTime.now());
			row.setScanType(scanType);
			badgeScanLogRepository.save(row);
		} catch (Exception e) {
			log.error("Error logging a till pass ({}): {}", grantedBy, e.getMessage(), e);
		}
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
			log.setGrantedBy(GrantedBy.BADGE);
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
