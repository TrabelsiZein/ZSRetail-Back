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
	private Long quantitySold;
	private BigDecimal revenue;
}

