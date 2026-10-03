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

	public HeadOfficeHeartbeatAnswerDTO(String storeCode, String serverTime, Boolean canEditMembers,
			Boolean canAdjustPoints) {
		super(storeCode, serverTime);
		this.canEditMembers = canEditMembers;
		this.canAdjustPoints = canAdjustPoints;
	}
}
