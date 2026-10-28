package com.digithink.zsretail.core.link.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1 (store orders): an order of a store to its head office as it travels up, POST /ho/orders, by codes, never a
 * database id. The head office takes an order number once: the same order sent again after a lost answer changes
 * nothing there. Answered by a {@link SalesCopyResultDTO} (documentNumber = number; accepted, or the reason of a
 * refusal). See docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoreOrderDTO {

	/** Store code, year and sequence, e.g. S01-2026-000012. */
	private String number;

	/** The store code (DEFAULT_LOCATION), the order's NAV location code; the head office checks it against X-Store-Code. */
	private String locationCode;

	private String note;

	/** yyyy-MM-ddTHH:mm:ss, store clock. */
	private String createdAt;

	/** yyyy-MM-ddTHH:mm:ss, store clock of the send. */
	private String sentAt;

	private List<Line> lines = new ArrayList<>();

	/** 3.1.1: the colis ordered, one per ERP item (an older head office ignores them). */
	private List<Colis> colis = new ArrayList<>();

	/** 3.1.1: a number of colis of an ERP item (ZEN's série: contenance pieces each). */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Colis {
		private String navItemNo;
		private String description;
		private Integer nbColis;
		/** The pieces in one colis, as the head office gave them; null when unknown (informative). */
		private Integer contenance;
		/** The colis the head office had made available when the store added them; null when none was given. */
		private Integer colisPossibles;
	}

	/** One variant ordered. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Line {
		/** The store's item code (navItemNo~variantCode for a ZEN variant). */
		private String itemCode;
		/** The ERP item number. */
		private String navItemNo;
		/** The ERP variant code; null for an item without variants. */
		private String variantCode;
		private String description;
		private String variantDescription;
		/** Up to 3 decimals, 6 travels as 6. */
		private BigDecimal quantity;
	}
}
