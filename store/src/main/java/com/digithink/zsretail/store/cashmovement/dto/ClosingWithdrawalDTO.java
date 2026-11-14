package com.digithink.zsretail.store.cashmovement.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, the opening amount: the responsible's "Retrait du tiroir" of a session (the session's details, its tickets X /
 * Z, the slip "Prélèvement de clôture" and its reprint).
 */
@Data
@NoArgsConstructor
public class ClosingWithdrawalDTO {
	private Long sessionId;
	private String sessionNumber;
	private String cashierName;
	/** The session's POS machine; null without one. */
	private String posMachineName;
	/** ALL, NONE or AMOUNT. */
	private String choice;
	private Double cashCounted;
	private Double amountTaken;
	private Double amountLeft;
	private String doneByName;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime doneAt;

	/** The slip's number; null when nothing was taken (no slip). */
	private String slipNumber;

	/**
	 * The drawer the answer left its cash in (the POS machine when the drawer belongs to it, else the cashier): the store
	 * ticket counts, per drawer, only its last answer as "Laissé dans le tiroir". Not sent.
	 */
	@com.fasterxml.jackson.annotation.JsonIgnore
	private String drawerKey;
}
