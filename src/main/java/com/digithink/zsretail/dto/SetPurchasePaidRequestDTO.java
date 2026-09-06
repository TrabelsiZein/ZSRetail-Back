package com.digithink.zsretail.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for setting paid amount/date on a purchase (standalone).
 */
@Data
@NoArgsConstructor
public class SetPurchasePaidRequestDTO {

	private Double paidAmount;
	private String paidDate; // ISO date-time string, e.g. "2025-02-24T14:30:00"
}
