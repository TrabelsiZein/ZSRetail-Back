package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 4: a member changed at a store that has the right (PUT /ho/loyalty/members/{cardNumber}). The
 * whole editable state, as the store's edit form sends it; active null leaves it as it is.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoyaltyMemberEditDTO {

	private String firstName;
	private String lastName;
	private String phone;
	private String email;
	private LocalDate birthDate;
	private String memberFunctionCode;
	private String memberFunctionName;
	private String customerCode;
	private Boolean active;
}
