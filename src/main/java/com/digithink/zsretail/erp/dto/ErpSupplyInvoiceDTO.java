package com.digithink.zsretail.erp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Invoices from the ERP: a posted invoice of the ERP to a franchise store, read by its number (the head office supplies
 * the store with it). Amounts as the ERP gives them; the three totals are the ERP's, never recomputed. warnings: what the
 * head office must look at before the invoice is used (a quantity not whole, prices including VAT, lines that do not add
 * up to the total).
 */
@Getter
@Setter
public class ErpSupplyInvoiceDTO {

	/** ITEM: a line of an item (stock and cost); OTHER: a line with an amount and no item (G/L account, resource...). */
	public enum LineType {
		ITEM, OTHER
	}

	private String number;
	private LocalDate documentDate;
	private LocalDate postingDate;
	private String customerNo;
	private String customerName;
	private BigDecimal totalExclVat;
	private BigDecimal totalVat;
	private BigDecimal totalInclVat;
	/** Null when the page does not give it. */
	private Boolean pricesIncludingVat;
	private List<Line> lines = new ArrayList<>();
	private List<String> warnings = new ArrayList<>();

	@Getter
	@Setter
	public static class Line {
		private Integer lineNo;
		private LineType type;
		/** The item number; null on an OTHER line. */
		private String itemCode;
		private String description;
		private BigDecimal quantity;
		private String unitOfMeasure;
		private BigDecimal unitPrice;
		private BigDecimal lineDiscountPercent;
		/** After the line discount, before the VAT (unless the prices include it). */
		private BigDecimal lineAmount;
	}
}
