package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 4: one loyalty movement of a store (a row of its loyalty_transaction), sent up by codes. The
 * head office applies it once, keyed by the calling store and {@link #key}, to the member of {@link #cardNumber} (or the
 * member that card was merged into).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoyaltyMovementCopyDTO {

	/** The store's key of the movement: its loyalty_transaction id. Unique per store. */
	private String key;

	private String cardNumber;

	/** EARNED, REDEEMED, ADJUSTED or REVERSED. */
	private String type;

	/** As in loyalty_transaction: always positive. */
	private Integer points;

	/** The signed effect on the balance: + for EARNED and points given back, - for REDEEMED, REVERSED and removals. */
	private Integer delta;

	private String salesNumber;
	private String returnNumber;
	private String programCode;

	/** Store clock when the movement was made. */
	private LocalDateTime date;

	private LocalDate expiryDate;
	private String description;
	private String createdBy;
}
