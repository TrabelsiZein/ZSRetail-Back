package com.digithink.zsretail.core.link.dto;

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
	/**
	 * 3.3.0, communication step 5: the consent asked on the store's form; null (not shown, an older store, a
	 * deactivation sent "as it is") leaves the head office's value. messageConsentBy: the store's user who changed it.
	 */
	private Boolean messageConsent;
	private String messageConsentBy;
}
