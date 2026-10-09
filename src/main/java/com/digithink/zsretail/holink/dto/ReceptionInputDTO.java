package com.digithink.zsretail.holink.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7A: the store's confirmation of a BL (POST /admin/deliveries/{id}/receive). A line absent from
 * the body, or sent without a quantity, is received as sent.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReceptionInputDTO {

	private String note;
	private List<Line> lines = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Line {
		private Integer lineNo;
		/** 2.2.1: up to 3 decimals when the store allows them (ALLOW_DECIMAL_QUANTITY). */
		private BigDecimal quantityReceived;

		public Line(Integer lineNo, BigDecimal quantityReceived) {
			this.lineNo = lineNo;
			this.quantityReceived = quantityReceived;
		}
	}
}
