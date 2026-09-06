package com.digithink.zsretail.analytics.dto;

import java.math.BigDecimal;

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
}

