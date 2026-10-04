package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Answer of POST /ho/heartbeat: the fields of GET /ho/ping (storeCode, serverTime) and, since step 4, the store's
 * loyalty rights set on the head office Stores page. A store of an older version reads the first two only.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HeadOfficeHeartbeatAnswerDTO extends HeadOfficePingDTO {

	/** Step 4: the store may change members (edit, deactivate) through the head office. */
	private Boolean canEditMembers;

	/** Step 4: the store may adjust points by hand (refused at the store in step 4 whatever the right). */
	private Boolean canAdjustPoints;

	/** Step 5: spending needs a balance refreshed from the head office in the last 2 minutes. */
	private Boolean redeemRequiresOnline;

	/** Enrol switch (2026-10-04): an enrol needs the head office's answer to its phone check. */
	private Boolean enrolRequiresOnline;

	public HeadOfficeHeartbeatAnswerDTO(String storeCode, String serverTime, Boolean canEditMembers,
			Boolean canAdjustPoints, Boolean redeemRequiresOnline, Boolean enrolRequiresOnline) {
		super(storeCode, serverTime);
		this.canEditMembers = canEditMembers;
		this.canAdjustPoints = canAdjustPoints;
		this.redeemRequiresOnline = redeemRequiresOnline;
		this.enrolRequiresOnline = enrolRequiresOnline;
	}
}
