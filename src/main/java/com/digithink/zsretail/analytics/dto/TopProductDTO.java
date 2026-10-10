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
public class TopProductDTO {

	private String itemCode;
	private String itemName;
	private String familyName;
	private BigDecimal quantitySold; // 2.2.1: with its decimals, written without trailing zeros
	private BigDecimal revenue;
}

