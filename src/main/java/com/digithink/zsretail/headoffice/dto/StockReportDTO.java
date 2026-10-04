package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, task 7A.5: a batch of a store's stock as it travels up (POST /ho/supply/stock), by item code only:
 * the items whose stock changed since the head office last accepted it, and the codes of the items the store no longer
 * has. No store code (the head office takes the authenticated store).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockReportDTO {

	/** yyyy-MM-ddTHH:mm:ss, store clock when the batch was read. */
	private String takenAt;
	private List<Item> items = new ArrayList<>();
	private List<String> removed = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Item {
		private String itemCode;
		private String itemName;
		/** The store's stock now; a null stock is sent as 0. */
		private Integer quantity;
		/** True for an item of the store's own (not from the head office). */
		private boolean own;
	}

	/** The head office's answer: how many items it saved and removed. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Answer {
		private Integer saved;
		private Integer removed;
	}
}
