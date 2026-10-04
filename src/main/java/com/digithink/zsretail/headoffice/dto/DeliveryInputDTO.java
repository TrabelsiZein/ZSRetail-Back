package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7A: a draft BL as the head office page sends it (POST and PUT /admin/headoffice/deliveries).
 * A line names its item by id or by code; the quantity is a whole number above 0.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeliveryInputDTO {

	private Long storeId;
	/** yyyy-MM-dd; today when absent. */
	private String documentDate;
	private String note;
	private List<Line> lines = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Line {
		private Long itemId;
		private String itemCode;
		private Integer quantity;

		public Line(String itemCode, Integer quantity) {
			this.itemCode = itemCode;
			this.quantity = quantity;
		}
	}
}
