package com.digithink.zsretail.model;

import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.model.enumeration.RecordOrigin;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Loyalty member entity - represents loyalty cardholders.
 * Completely independent from Customer; any walk-in person can have a loyalty card.
 * Optionally linked to a formal Customer for reporting purposes.
 */
@Entity
@Table(name = "loyalty_member")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class LoyaltyMember extends _BaseEntity {

	@Column(name = "card_number", nullable = false, unique = true, length = 50)
	private String cardNumber;

	@Column(name = "first_name", nullable = false, length = 100)
	private String firstName;

	@Column(name = "last_name", nullable = false, length = 100)
	private String lastName;

	@Column(length = 30)
	private String phone;

	@Column(length = 150)
	private String email;

	@Column(name = "birth_date")
	private LocalDate birthDate;

	/** Optional link to a formal Customer (e.g. company employee with loyalty card) */
	@ManyToOne
	@JoinColumn(name = "customer_id")
	private Customer customer;

	@ManyToOne
	@JoinColumn(name = "member_function_id")
	private MemberFunction memberFunction;

	@Column(name = "loyalty_points", nullable = false)
	private Integer loyaltyPoints = 0;

	@Column(name = "total_points_earned", nullable = false)
	private Integer totalPointsEarned = 0;

	@Column(name = "total_points_redeemed", nullable = false)
	private Integer totalPointsRedeemed = 0;

	/** Reserved for future ERP synchronization */
	@Column(name = "erp_external_id")
	private String erpExternalId;

	/**
	 * Head office plan, step 4: on a store whose loyalty is owned by the head office, HEAD_OFFICE for a member of the
	 * network register (received from the head office, or enrolled here and sent up); null or LOCAL for a member made
	 * before the switch, set inactive at the first pull. Null everywhere else.
	 */
	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private RecordOrigin origin;
}
