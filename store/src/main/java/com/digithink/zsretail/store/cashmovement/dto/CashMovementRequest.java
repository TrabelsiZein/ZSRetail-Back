package com.digithink.zsretail.store.cashmovement.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, POS machines step 5: POST /cash-movement. type: PRELEVEMENT, DEPENSE or APPORT; amount above 0 (to the
 * millime); note (required for DEPENSE); sessionId (optional: when sent, it must be the logged user's own open session);
 * tillGrant: the badge scan's grant when the role does not hold till:cash-movement.
 */
@Data
@NoArgsConstructor
public class CashMovementRequest {
	private String type;
	private Double amount;
	private String note;
	private Long sessionId;
	private String tillGrant;
}
