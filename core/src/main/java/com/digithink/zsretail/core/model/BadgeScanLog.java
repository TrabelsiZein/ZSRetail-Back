package com.digithink.zsretail.core.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Enumerated;
import javax.persistence.EnumType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.model.enumeration.GrantedBy;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * BadgeScanLog entity - every till authorisation for audit purposes: a badge scan (grantedBy BADGE, success or refusal)
 * or, since step C (3.1.0), a pass on the logged user's role right (grantedBy ROLE: no badge code, the owner is the
 * logged user)
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class BadgeScanLog extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "scanned_by_user_id", nullable = false)
	private UserAccount scannedBy; // Who performed the scan

	// Step C (3.1.0): null for a pass on the role's right (db/3.1.0/update.sql and TillSchemaUpgrade relax the NOT NULL)
	@Column(name = "scanned_badge_code", nullable = true, length = 50)
	private String scannedBadgeCode; // Which badge was scanned

	/** Step C (3.1.0): ROLE or BADGE; the rows of before are scans (set to BADGE by the script and at the start). */
	@Enumerated(EnumType.STRING)
	@Column(name = "granted_by", length = 10, nullable = true)
	private GrantedBy grantedBy;

	@ManyToOne
	@JoinColumn(name = "scanned_user_id", nullable = true)
	private UserAccount scannedUser; // Owner of scanned badge (nullable if badge doesn't exist)

	@Enumerated(EnumType.STRING)
	@Column(name = "functionality", nullable = false)
	private BadgePermission functionality; // Which feature was accessed

	@Column(name = "success", nullable = false)
	private Boolean success; // Success or failure

	@Column(name = "failure_reason", length = 500, nullable = true)
	private String failureReason; // "BADGE_NOT_EXISTS", "BADGE_NO_ACCESS", "BADGE_EXPIRED", "BADGE_REVOKED", "BADGE_OWNER_INACTIVE"

	@Column(name = "session_id", nullable = true)
	private Long sessionId; // CashierSession ID (nullable)

	@Column(name = "timestamp", nullable = false)
	private LocalDateTime timestamp = LocalDateTime.now();

	@Column(name = "scan_type", length = 20, nullable = false)
	private String scanType = "BARCODE"; // "SCAN" for a scan, "ROLE" for a pass on the role's right (step C)
}

