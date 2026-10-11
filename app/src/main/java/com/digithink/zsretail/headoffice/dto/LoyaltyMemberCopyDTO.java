package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.digithink.zsretail.model.LoyaltyMember;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 4: a loyalty member by business codes, never a database id. Travels down (copies down of the
 * LOYALTY domain, record code MEMBER:&lt;card&gt;, with the head office balance), up (a member enrolled at a store, balance
 * 0: points travel as movements) and in the answers of the live questions. The member function and the customer travel
 * by code.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoyaltyMemberCopyDTO {

	public static final String KIND = "MEMBER";
	public static final String CODE_PREFIX = KIND + ":";

	/** Always MEMBER: tells the store which record of the LOYALTY domain this is. */
	private String kind = KIND;

	private String cardNumber;
	private String firstName;
	private String lastName;
	private String phone;
	private String email;
	private LocalDate birthDate;
	private String memberFunctionCode;
	private String memberFunctionName;
	private String customerCode;

	/** The head office balance and totals (0 in an upload). */
	private Integer loyaltyPoints;
	private Integer totalPointsEarned;
	private Integer totalPointsRedeemed;

	private Boolean active;

	/** When the member was created where it was enrolled. */
	private LocalDateTime enrolledAt;

	/** The record code of a member in the LOYALTY copies down. */
	public static String recordCode(String cardNumber) {
		return CODE_PREFIX + cardNumber;
	}

	public static LoyaltyMemberCopyDTO of(LoyaltyMember member) {
		LoyaltyMemberCopyDTO copy = new LoyaltyMemberCopyDTO();
		copy.setCardNumber(member.getCardNumber());
		copy.setFirstName(member.getFirstName());
		copy.setLastName(member.getLastName());
		copy.setPhone(member.getPhone());
		copy.setEmail(member.getEmail());
		copy.setBirthDate(member.getBirthDate());
		if (member.getMemberFunction() != null) {
			copy.setMemberFunctionCode(member.getMemberFunction().getCode());
			copy.setMemberFunctionName(member.getMemberFunction().getName());
		}
		if (member.getCustomer() != null) {
			copy.setCustomerCode(member.getCustomer().getCustomerCode());
		}
		copy.setLoyaltyPoints(member.getLoyaltyPoints());
		copy.setTotalPointsEarned(member.getTotalPointsEarned());
		copy.setTotalPointsRedeemed(member.getTotalPointsRedeemed());
		copy.setActive(member.getActive());
		copy.setEnrolledAt(member.getCreatedAt());
		return copy;
	}
}
