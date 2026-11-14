package com.digithink.zsretail.store.cashmovement.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, POS machines step 5: one cash movement as the till's slip, the session's details and the Journal's day show
 * it. No balance and no session total: the cashier's count stays blind.
 */
@Data
@NoArgsConstructor
public class CashMovementDTO {
	private Long id;
	private String number;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime movedAt;

	/** PRELEVEMENT, DEPENSE or APPORT. */
	private String type;
	/** IN (APPORT) or OUT. */
	private String direction;
	private Double amount;
	private String note;

	private Long sessionId;
	private String sessionNumber;
	private String cashierName;
	/** The session's POS machine; null without one. */
	private String posMachineName;
	/** Who authorised it: the cashier's own role (ROLE) or the badge's owner (BADGE). */
	private String authorisedByName;
	private String grantedBy;
}
