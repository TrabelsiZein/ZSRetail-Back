package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7A: a store's confirmation of a BL as it travels up (POST /ho/supply/confirmations), by the BL
 * number and line numbers, never a database id; no store code (the head office takes the authenticated store). Dates
 * as ISO strings, store clock.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeliveryConfirmationDTO {

	private String number;
	/** yyyy-MM-ddTHH:mm:ss, store clock. */
	private String receivedAt;
	/** The store user who confirmed (login). */
	private String receivedBy;
	private String note;
	private List<Line> lines = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Line {
		private Integer lineNo;
		private String itemCode;
		private Integer quantityReceived;

		public Line(Integer lineNo, String itemCode, Integer quantityReceived) {
			this.lineNo = lineNo;
			this.itemCode = itemCode;
			this.quantityReceived = quantityReceived;
		}
	}
}
