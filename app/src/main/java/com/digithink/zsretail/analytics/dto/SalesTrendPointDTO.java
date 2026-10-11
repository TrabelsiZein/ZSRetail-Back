package com.digithink.zsretail.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesTrendPointDTO {

	@JsonFormat(pattern = "yyyy-MM-dd")
	private LocalDate date;
	private BigDecimal revenue;
	private Long transactionCount;
}

