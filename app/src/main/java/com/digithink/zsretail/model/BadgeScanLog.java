package com.digithink.zsretail.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Enumerated;
import javax.persistence.EnumType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.model.enumeration.BadgePermission;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * BadgeScanLog entity - tracks all badge scan attempts for audit purposes
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class BadgeScanLog extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "scanned_by_user_id", nullable = false)
	private UserAccount scannedBy; // Who performed the scan

	@Column(name = "scanned_badge_code", nullable = false, length = 50)
	private String scannedBadgeCode; // Which badge was scanned

	@ManyToOne
	@JoinColumn(name = "scanned_user_id", nullable = true)
	private UserAccount scannedUser; // Owner of scanned badge (nullable if badge doesn't exist)

	@Enumerated(EnumType.STRING)
	@Column(name = "functionality", nullable = false)
	private BadgePermission functionality; // Which feature was accessed

	@Column(name = "success", nullable = false)
	private Boolean success; // Success or failure

	@Column(name = "failure_reason", length = 500, nullable = true)
	private String failureReason; // "BADGE_NOT_EXISTS", "BADGE_NO_ACCESS", "BADGE_EXPIRED", "BADGE_REVOKED"

	@Column(name = "session_id", nullable = true)
	private Long sessionId; // CashierSession ID (nullable)

	@Column(name = "timestamp", nullable = false)
	private LocalDateTime timestamp = LocalDateTime.now();

	@Column(name = "scan_type", length = 20, nullable = false)
	private String scanType = "BARCODE"; // "BARCODE" or "QR_CODE"
}

