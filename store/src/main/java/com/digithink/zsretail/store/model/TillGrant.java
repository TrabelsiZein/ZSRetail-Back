package com.digithink.zsretail.store.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Step D (3.1.0): what a badge scan authorised, kept by the server: one action, for the cashier session of the user
 * who scanned, used once by the request doing the action (the sale or pending save of a discount, the return, the
 * close with a difference). Good until used, until its session is no longer the open one, or until
 * {@link #expiresAt} (General Setup TILL_GRANT_MAX_MINUTES after the scan). The till holds the token; the table holds
 * only its SHA-256 ({@link #tokenHash}), so a token cannot be read back from the database.
 */
@Entity
@Table(name = "till_grant")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class TillGrant extends _BaseEntity {

	/** SHA-256 of the token, hex (64). */
	@Column(name = "token_hash", nullable = false, unique = true, length = 64)
	private String tokenHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, length = 50)
	private BadgePermission action;

	/** The cashier session the grant belongs to: the open session of the user who scanned. */
	@Column(name = "session_id", nullable = false)
	private Long sessionId;

	/** The user who scanned the badge at the till (the session's cashier). */
	@ManyToOne
	@JoinColumn(name = "scanned_by_user_id", nullable = false)
	private UserAccount scannedBy;

	/** The badge's owner, whose role holds the right. */
	@ManyToOne
	@JoinColumn(name = "badge_owner_id", nullable = false)
	private UserAccount badgeOwner;

	/** The badge scanned (written to the scan log when the grant is used). */
	@Column(name = "badge_code", nullable = false, length = 50)
	private String badgeCode;

	@Column(name = "issued_at", nullable = false)
	private LocalDateTime issuedAt;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	/** When a request used it; null while unused. */
	@Column(name = "consumed_at")
	private LocalDateTime consumedAt;

	/** What used it, e.g. "SALE T-26101800012", "RETURN R-...", "CLOSE SESSION...". */
	@Column(name = "consumed_by_ref", length = 100)
	private String consumedByRef;
}
