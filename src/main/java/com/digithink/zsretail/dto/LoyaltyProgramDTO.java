package com.digithink.zsretail.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Admin view of a loyalty program. Includes computed flags indicating which
 * actions are permitted on this record (edit, delete, deactivate).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyProgramDTO {

	private Long id;
	private String programCode;
	private String name;
	private String description;
	private String startDate;
	private String endDate;
	private Double pointsPerDinar;
	private Integer pointValueMillimes;
	private Integer minimumRedemptionPoints;
	private Double maximumRedemptionPercentage;
	private Integer pointsExpiryDays;
	private Boolean active;

	/** Number of loyalty transactions that reference this program. 0 = never used. */
	private long transactionCount;

	/** True when the program is currently applicable (active + within start/end window). */
	private boolean current;

	/** All fields may be edited (only true when transactionCount == 0). */
	private boolean canEditAll;

	/** Safe fields (name, description, endDate) may be edited. Always true unless program has ended. */
	private boolean canEditSafe;

	/** Program may be hard-deleted (only true when transactionCount == 0). */
	private boolean canDelete;

	/** Program may be deactivated (only true when it is currently active). */
	private boolean canDeactivate;
}
