package com.digithink.zsretail.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * View DTO for a single loyalty transaction (audit log entry).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyTransactionDTO {

	private Long id;
	private String type;
	private Integer points;
	private Integer balanceBefore;
	private Integer balanceAfter;
	private String description;
	private String expiryDate;
	private String createdAt;
	private String createdBy;

	// Related sale info (optional)
	private Long salesHeaderId;
	private String salesNumber;

	// Related return info (optional)
	private Long returnHeaderId;
	private String returnNumber;

	// Loyalty program info
	private String programName;

	// Member info (populated on cross-member queries)
	private Long memberId;
	private String memberCardNumber;
	private String memberFullName;
}
