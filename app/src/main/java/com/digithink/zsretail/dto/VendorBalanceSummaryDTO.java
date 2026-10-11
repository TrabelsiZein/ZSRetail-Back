package com.digithink.zsretail.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-vendor summary for AP / vendor balance report.
 * totalPurchased = sum of purchase totalAmount; totalPaid = sum of paidAmount; unpaid = totalPurchased - totalPaid.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorBalanceSummaryDTO {

	private Long vendorId;
	private String vendorCode;
	private String vendorName;
	private Double totalPurchased;
	private Double totalPaid;
	private Double unpaid;
}
