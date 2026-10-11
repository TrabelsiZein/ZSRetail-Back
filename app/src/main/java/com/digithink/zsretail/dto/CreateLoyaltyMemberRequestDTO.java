package com.digithink.zsretail.dto;

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
}
