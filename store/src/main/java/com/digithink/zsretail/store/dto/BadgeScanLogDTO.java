package com.digithink.zsretail.store.dto;

import java.time.LocalDateTime;

import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.UserAccount;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Step S (3.1.0): one row of the badge scan history (GET /badge/scan-history), flat, with the field names the admin page
 * BadgeScanHistory.vue reads. Never the badge code (a credential), never a whole UserAccount (it carried the password
 * hash and every user's badge).
 */
@Data
@NoArgsConstructor
public class BadgeScanLogDTO {

	private Long id;
	private LocalDateTime timestamp;
	/** The logged user who scanned. */
	private String scannedByUsername;
	private String scannedByFullName;
	/** The owner of the badge scanned; null when the code matched no badge. */
	private String scannedUserUsername;
	private String scannedUserFullName;
	/** The action asked (BadgePermission name). */
	private String functionality;
	private Boolean success;
	/** BADGE_NOT_EXISTS, BADGE_REVOKED, BADGE_EXPIRED, BADGE_NO_ACCESS; null on success. */
	private String failureReason;
	private Long sessionId;

	public static BadgeScanLogDTO from(BadgeScanLog log) {
		BadgeScanLogDTO dto = new BadgeScanLogDTO();
		dto.setId(log.getId());
		dto.setTimestamp(log.getTimestamp());
		UserAccount by = log.getScannedBy();
		if (by != null) {
			dto.setScannedByUsername(by.getUsername());
			dto.setScannedByFullName(by.getFullName());
		}
		UserAccount owner = log.getScannedUser();
		if (owner != null) {
			dto.setScannedUserUsername(owner.getUsername());
			dto.setScannedUserFullName(owner.getFullName());
		}
		dto.setFunctionality(log.getFunctionality() != null ? log.getFunctionality().name() : null);
		dto.setSuccess(log.getSuccess());
		dto.setFailureReason(log.getFailureReason());
		dto.setSessionId(log.getSessionId());
		return dto;
	}
}
