package com.digithink.zsretail.model;

import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

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
}
