package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One earning tier of a loyalty program: when the ticket amount is strictly
 * above {@code thresholdAmount}, {@code pointsPerDinar} applies to the whole
 * ticket. A ticket exactly on a threshold stays in the lower tier.
 */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyEarningTier {

	/** Ticket amount in TND (fiscal stamp excluded) above which this tier applies */
	@Column(name = "threshold_amount", nullable = false)
	private Double thresholdAmount;

	/** Points earned per TND on the whole ticket when this tier applies */
	@Column(name = "points_per_dinar", nullable = false)
	private Double pointsPerDinar;
}
