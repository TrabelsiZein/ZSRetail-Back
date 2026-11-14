package com.digithink.zsretail.store.cashmovement.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, the opening amount: the float proposed to a session (GET /cashier-session/opening-proposal before the opening;
 * then the session's details, tickets and the Journal's day with openingCash and openingGap).
 */
@Data
@NoArgsConstructor
public class OpeningFloatDTO {
	private Double proposedFloat;
	private Long sourceSessionId;
	private String sourceSessionNumber;

	/** When the source session was verified (the float left then). */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime sourceVerifiedAt;

	/** The session that received it; null on a proposal. */
	private Long sessionId;
	private String sessionNumber;
	private String cashierName;
	private String posMachineName;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime openedAt;

	/** The opening cash typed; null on a proposal. */
	private Double openingCash;
	/** openingCash - proposedFloat; null on a proposal. */
	private Double openingGap;
}
