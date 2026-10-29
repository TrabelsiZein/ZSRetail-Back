package com.digithink.zsretail.core.link.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1 (store orders): what became of a store's order at the head office, as it travels down to that store only
 * (domain SUPPLY, record ORD:&lt;number&gt;, kind STORE_ORDER). The number is orderNumber, never "number": a store that
 * does not know this kind reads no BL number in it and refuses the record. See docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoreOrderStatusCopyDTO {

	public static final String KIND = "STORE_ORDER";
	public static final String RECORD_PREFIX = "ORD:";

	private String kind = KIND;
	private String orderNumber;
	/**
	 * SENT (at the head office, not in NAV yet), POSTED (in NAV, navOrderNo set), ERROR (errorMessage set) or DELIVERED
	 * (3.1.1: NAV invoiced it, invoiceNo and deliveredAt set). A store of an earlier build refuses DELIVERED (the record in
	 * error, retried) until it is upgraded.
	 */
	private String status;
	/** The NAV document number once posted. */
	private String navOrderNo;
	/** Why the head office could not post it into NAV. */
	private String errorMessage;
	/** NAV's message on the order when it was posted. */
	private String navMessage;
	/**
	 * NAV's answer per line when it was posted, by the line's item code; null when NAV gave none (not posted yet, posted
	 * by hand).
	 */
	private List<Line> lines;
	/**
	 * 3.1.1: NAV's answer per colis row when it was posted, by ERP item number; null when NAV gave none on a colis row.
	 */
	private List<Colis> colis;
	/** DELIVERED: the ERP invoice of the order (the number the store receives it under, ERPINV:). */
	private String invoiceNo;
	/** DELIVERED: the invoice's posting date, yyyy-MM-dd. */
	private String deliveredAt;
	/** yyyy-MM-ddTHH:mm:ss, head office clock of the status. */
	private String statusAt;

	/** NAV's answer on one line of the order. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Line {
		private String itemCode;
		/** The quantity NAV accepted; null when it gave none. */
		private BigDecimal acceptedQty;
		private String message;
	}

	/** 3.1.1: NAV's answer on one colis row of the order. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Colis {
		private String navItemNo;
		/** The colis NAV accepted; null when it gave none. */
		private Integer acceptedColis;
		/** The pieces NAV accepted on the colis line (its quantite); null when it gave none. */
		private BigDecimal acceptedQty;
		/** NAV's colis number (its numeroColis); null when it gave none. */
		private String colisNumber;
		private String message;
	}

	public static String recordCode(String orderNumber) {
		return RECORD_PREFIX + orderNumber;
	}
}
