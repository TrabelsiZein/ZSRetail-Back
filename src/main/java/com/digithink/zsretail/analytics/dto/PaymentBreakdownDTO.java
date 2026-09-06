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
public class PaymentBreakdownDTO {

	private String methodCode;
	private String methodName;
	private BigDecimal totalAmount;
	private BigDecimal percentage;
}

