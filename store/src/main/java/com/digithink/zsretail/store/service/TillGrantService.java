package com.digithink.zsretail.store.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.store.dto.TillGrantDTO;
import com.digithink.zsretail.store.exception.TillRightRequiredException;
import com.digithink.zsretail.store.exception.TillRightRequiredException.Reason;
import com.digithink.zsretail.store.model.TillGrant;
import com.digithink.zsretail.store.repository.TillGrantRepository;

import lombok.extern.log4j.Log4j2;

/**
 * Step D (3.1.0): the grants of the badge scans (docs/modules/badge-permissions.md). A successful scan of an action the
 * server checks on its request gives a grant: a random token (32 bytes, base64url) the till holds, its SHA-256 here,
 * one action, the scanning user's open session, single use, good for General Setup TILL_GRANT_MAX_MINUTES (240 when
 * absent or not a positive number).
 */
@Service
@Log4j2
public class TillGrantService {

	/** General Setup: the minutes a grant stays good when no request used it. */
	public static final String MAX_MINUTES = "TILL_GRANT_MAX_MINUTES";
	public static final int DEFAULT_MAX_MINUTES = 240;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	private TillGrantRepository tillGrantRepository;

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	/**
	 * The grant of a successful scan, or null when none is given: an action no request checks (the customer list), or
	 * no open session of the scanning user (no request of the till could use it).
	 */
	public TillGrantDTO issue(BadgePermission action, UserAccount scannedBy, UserAccount badgeOwner, String badgeCode,
			Long sessionId) {
		if (action == null || !action.checkedOnRequest() || sessionId == null || scannedBy == null
				|| badgeOwner == null) {
			return null;
		}
		String token = newToken();
		LocalDateTime now = LocalDateTime.now();
		TillGrant grant = new TillGrant();
		grant.setTokenHash(hash(token));
		grant.setAction(action);
		grant.setSessionId(sessionId);
		grant.setScannedBy(scannedBy);
		grant.setBadgeOwner(badgeOwner);
		grant.setBadgeCode(badgeCode);
		grant.setIssuedAt(now);
		grant.setExpiresAt(now.plusMinutes(maxMinutes()));
		grant.setCreatedBy(scannedBy.getUsername());
		grant.setUpdatedBy(scannedBy.getUsername());
		tillGrantRepository.save(grant);
		return new TillGrantDTO(token, action.name(), badgeOwner.getId(), now, grant.getExpiresAt());
	}

	/** TILL_GRANT_MAX_MINUTES, else {@link #DEFAULT_MAX_MINUTES}. */
	int maxMinutes() {
		return generalSetupRepository.findByCode(MAX_MINUTES).map(row -> {
			try {
				int minutes = Integer.parseInt(row.getValeur() == null ? "" : row.getValeur().trim());
				return minutes > 0 ? minutes : DEFAULT_MAX_MINUTES;
			} catch (NumberFormatException e) {
				return DEFAULT_MAX_MINUTES;
			}
		}).orElse(DEFAULT_MAX_MINUTES);
	}

	/**
	 * The grant of a token for a request doing {@code action} in the session {@code sessionId}: known, of this action and
	 * this session, unused, not expired; else {@link TillRightRequiredException} with the reason.
	 */
	public TillGrant valid(String token, BadgePermission action, Long sessionId, Integer lineIndex) {
		if (token == null || token.trim().isEmpty()) {
			throw new TillRightRequiredException(action, Reason.NO_GRANT, lineIndex);
		}
		TillGrant grant = tillGrantRepository.findByTokenHash(hash(token.trim()))
				.orElseThrow(() -> new TillRightRequiredException(action, Reason.GRANT_UNKNOWN, lineIndex));
		Reason refused = null;
		if (grant.getAction() != action) {
			refused = Reason.GRANT_OTHER_ACTION;
		} else if (sessionId == null || !sessionId.equals(grant.getSessionId())) {
			refused = Reason.GRANT_OTHER_SESSION;
		} else if (grant.getConsumedAt() != null) {
			refused = Reason.GRANT_USED;
		} else if (!LocalDateTime.now().isBefore(grant.getExpiresAt())) {
			refused = Reason.GRANT_EXPIRED;
		}
		if (refused != null) {
			log.warn("Till grant {} refused for {} in session {}: {}", grant.getId(), action, sessionId, refused);
			throw new TillRightRequiredException(action, refused, lineIndex);
		}
		return grant;
	}

	/** Uses the grant for {@code ref}; refused GRANT_USED when another request used it first. */
	public void consume(TillGrant grant, String ref, Integer lineIndex) {
		String what = ref != null && ref.length() > 100 ? ref.substring(0, 100) : ref;
		if (tillGrantRepository.consume(grant.getId(), LocalDateTime.now(), what) != 1) {
			throw new TillRightRequiredException(grant.getAction(), Reason.GRANT_USED, lineIndex);
		}
	}

	static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** SHA-256 of the token, lower-case hex. */
	static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(64);
			for (byte b : digest) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
