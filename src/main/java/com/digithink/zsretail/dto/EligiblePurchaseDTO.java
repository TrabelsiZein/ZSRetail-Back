package com.digithink.zsretail.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for GET /admin/purchase-invoices/eligible-purchases. Exposes only fields
 * needed for the eligible purchases table.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EligiblePurchaseDTO {

	private Long id;
	private String purchaseNumber;
	private LocalDateTime purchaseDate;
	private Double totalAmount;
}
