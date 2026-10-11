package com.digithink.zsretail.dto;

import lombok.Data;

/**
 * DTO for admin manual point adjustment.
 * delta is a signed integer: positive to add points, negative to remove.
 */
@Data
public class LoyaltyAdjustmentRequestDTO {

	/** Signed delta: positive = add, negative = remove */
	private Integer delta;

	/** Mandatory reason for the adjustment */
	private String reason;
}
