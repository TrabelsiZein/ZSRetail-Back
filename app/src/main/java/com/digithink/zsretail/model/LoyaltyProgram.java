package com.digithink.zsretail.model;

import java.time.LocalDate;
import java.util.List;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.Table;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Loyalty program entity - the "rate card" configuration for the loyalty program.
 * Only one program can be active at a time. Creating a new program closes the previous one.
 * Past programs are immutable (audit trail).
 */
@Entity
@Table(name = "loyalty_program")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class LoyaltyProgram extends _BaseEntity {

	@Column(name = "program_code", nullable = false, unique = true, length = 50)
	private String programCode;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	/** Null means this is the currently active program */
	@Column(name = "end_date")
	private LocalDate endDate;

	/** Points earned per TND spent (e.g. 1.0 = 1 point per TND) */
	@Column(name = "points_per_dinar", nullable = false)
	private Double pointsPerDinar = 1.0;

	/** TND value of 1 point expressed in millimes (e.g. 10 = 0.010 TND per point → 100 pts = 1 TND) */
	@Column(name = "point_value_millimes", nullable = false)
	private Integer pointValueMillimes = 10;

	/** Minimum number of points required before redemption is allowed */
	@Column(name = "minimum_redemption_points", nullable = false)
	private Integer minimumRedemptionPoints = 100;

	/** Maximum percentage of the sale total that can be paid with loyalty points (e.g. 30.0 = 30%) */
	@Column(name = "maximum_redemption_percentage", nullable = false)
	private Double maximumRedemptionPercentage = 30.0;

	/** Days after earning before points expire. Null = points never expire */
	@Column(name = "points_expiry_days")
	private Integer pointsExpiryDays;

	/**
	 * Optional earning tiers ("above X TND: Y points per TND", applied to the whole
	 * ticket). Empty = pointsPerDinar for every ticket (flat program).
	 * Null in a create/update request = not provided (update leaves tiers unchanged).
	 * Loaded eagerly with its own SELECT so it is never join-fetched with other collections.
	 */
	@ElementCollection(fetch = FetchType.EAGER)
	@Fetch(FetchMode.SELECT)
	@CollectionTable(name = "loyalty_program_tier", joinColumns = @JoinColumn(name = "loyalty_program_id"))
	private List<LoyaltyEarningTier> earningTiers;

	/**
	 * Head office plan, step 4: HEAD_OFFICE for the program received from the head office on a store whose loyalty it
	 * owns; null or LOCAL otherwise (set inactive at the first pull there). Never read from JSON.
	 */
	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private RecordOrigin origin;
}
