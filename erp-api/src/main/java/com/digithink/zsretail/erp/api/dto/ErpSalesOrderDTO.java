package com.digithink.zsretail.erp.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1 (store orders): a sales order of the ERP for a franchise store, posted by the head office
 * (ErpConnector.postSalesOrder). externalDocumentNo is the store's order number: the connector looks it up first and
 * reuses the ERP's order of that number, so an order posted again after a lost answer is never doubled.
 */
@Data
@NoArgsConstructor
public class ErpSalesOrderDTO {

	/** The store's location in the ERP (its store code). */
	private String locationCode;

	/** The store's ERP customer number. */
	private String customerNo;

	/** The store's order number, e.g. S01-2026-000012. */
	private String externalDocumentNo;

	private LocalDate orderDate;

	private List<Line> lines = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Line {
		/** The ERP item number. */
		private String itemNo;
		/** The ERP variant code; empty for an item without variants. */
		private String variantCode;
		private BigDecimal quantity;
	}
}
