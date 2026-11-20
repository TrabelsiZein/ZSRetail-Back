package com.digithink.zsretail.core.dto;

import lombok.Data;

/**
 * DTO for creating a new loyalty member (from POS or admin).
 */
@Data
public class CreateLoyaltyMemberRequestDTO {

	private String firstName;
	private String lastName;
	private String phone;
	private String email;
	private String birthDate;

	/** Optional: link to an existing Customer by ID */
	private Long customerId;

	private Long memberFunctionId;
	/**
	 * 3.3.0, communication step 5: the member accepts to receive messages. Null (the tick not shown: the module off, an
	 * older screen): nothing asked, a new member without consent, an existing one unchanged.
	 */
	private Boolean messageConsent;
}
