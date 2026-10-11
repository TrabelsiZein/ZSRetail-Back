package com.digithink.zsretail.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * View DTO for a loyalty member (POS search results and admin list).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyMemberDTO {

	private Long id;
	private String cardNumber;
	private String firstName;
	private String lastName;
	private String fullName;
	private String phone;
	private String email;
	private String birthDate;
	private Integer loyaltyPoints;
	private Integer totalPointsEarned;
	private Integer totalPointsRedeemed;
	private Double pointsValueDinars;
	private Boolean active;
	private String enrolledAt;

	// Linked customer info (optional)
	private Long customerId;
	private String customerCode;
	private String customerName;

	// Member function
	private Long memberFunctionId;
	private String memberFunctionName;
}
