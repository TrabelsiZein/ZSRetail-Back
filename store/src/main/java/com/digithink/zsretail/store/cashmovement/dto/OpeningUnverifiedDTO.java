package com.digithink.zsretail.store.cashmovement.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, the differences, step A: the previous session of a drawer, closed and not verified: before an opening (the
 * opening proposal's unverified* fields) or kept on the session opened then (the session's details, openingUnverified).
 */
@Data
@NoArgsConstructor
public class OpeningUnverifiedDTO {
	private Long previousSessionId;
	private String previousSessionNumber;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime previousClosedAt;

	/** The previous session's POS machine; null without one. */
	private String previousPosMachineName;
}
