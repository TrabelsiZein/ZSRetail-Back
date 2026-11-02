package com.digithink.zsretail.store.analytics.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsSummaryDTO {

	private BigDecimal revenue;
	private BigDecimal revenueDelta;
	private Long transactionCount;
	private BigDecimal transactionCountDelta;
	private BigDecimal avgBasket;
	private BigDecimal avgBasketDelta;
	private BigDecimal totalReturns;
	private BigDecimal totalReturnsDelta;
	private BigDecimal netRevenue;
	private BigDecimal netRevenueDelta;

	// 3.1.2, the margin of the period (JournalCosts, as the Journal): only where the store keeps its own stock and for
	// read:admin-margins, else absent; absent too when the period has no costed line.

	/** Gross margin before VAT: the HT received on the costed lines - their cost, returns subtracted. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Double margin;

	/** margin / HT x 100, one decimal (the Journal's marginPercent); absent with a line without cost or an HT of 0. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Double marginRate;

	/** Lines with no unit cost (sold before 3.1.2 or an item without CMP); the stamp never counts. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Long linesWithoutCost;
}

