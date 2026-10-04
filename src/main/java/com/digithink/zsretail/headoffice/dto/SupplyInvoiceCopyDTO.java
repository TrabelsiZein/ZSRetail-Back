package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.List;

import com.digithink.zsretail.headoffice.model.HoSupplyInvoice;
import com.digithink.zsretail.headoffice.model.HoSupplyInvoiceLine;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7B: a supply invoice as it travels down to its store only (domain SUPPLY, record
 * INV:&lt;number&gt;, kind INVOICE), by codes, never a database id. Its lines are also the lines of the admin view. Dates
 * as ISO strings.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SupplyInvoiceCopyDTO {

	public static final String KIND = "INVOICE";
	public static final String RECORD_PREFIX = "INV:";

	private String kind = KIND;
	private String invoiceNumber;
	/** yyyy-MM-dd. */
	private String invoiceDate;
	private String sellerName;
	private String sellerTaxNumber;
	private String sellerAddress;
	private String buyerName;
	private String buyerTaxNumber;
	private String buyerAddress;
	private String note;
	private List<String> deliveryNumbers = new ArrayList<>();
	private List<Line> lines = new ArrayList<>();
	private Double subtotal;
	private Double taxAmount;
	private Double totalAmount;

	public static String recordCode(String number) {
		return RECORD_PREFIX + number;
	}

	/** The invoice number of a record code; null when it is not one. */
	public static String numberOf(String recordCode) {
		return recordCode != null && recordCode.startsWith(RECORD_PREFIX) && recordCode.length() > RECORD_PREFIX.length()
				? recordCode.substring(RECORD_PREFIX.length())
				: null;
	}

	public static SupplyInvoiceCopyDTO of(HoSupplyInvoice invoice) {
		SupplyInvoiceCopyDTO copy = new SupplyInvoiceCopyDTO();
		copy.invoiceNumber = invoice.getInvoiceNumber();
		copy.invoiceDate = invoice.getInvoiceDate() == null ? null : invoice.getInvoiceDate().toString();
		copy.sellerName = invoice.getSellerName();
		copy.sellerTaxNumber = invoice.getSellerTaxNumber();
		copy.sellerAddress = invoice.getSellerAddress();
		copy.buyerName = invoice.getBuyerName();
		copy.buyerTaxNumber = invoice.getBuyerTaxNumber();
		copy.buyerAddress = invoice.getBuyerAddress();
		copy.note = invoice.getNote();
		for (HoSupplyInvoiceLine line : invoice.getLines()) {
			if (line.getDeliveryNumber() != null && !copy.deliveryNumbers.contains(line.getDeliveryNumber())) {
				copy.deliveryNumbers.add(line.getDeliveryNumber());
			}
			copy.lines.add(Line.of(line));
		}
		copy.lines.sort((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()));
		copy.subtotal = invoice.getSubtotal();
		copy.taxAmount = invoice.getTaxAmount();
		copy.totalAmount = invoice.getTotalAmount();
		return copy;
	}

	/**
	 * One line: a BL line (deliveryNumber) or the tax stamp (deliveryNumber null). In a preview only, a BL line of an item
	 * without a supply price: missingPrice true, no line number, no price or amounts, outside the totals.
	 */
	@Data
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Line {
		private Integer lineNo;
		private String deliveryNumber;
		private String itemCode;
		private String itemName;
		private Integer quantity;
		private Double unitPrice;
		private Integer vatPercent;
		private Double vatAmount;
		private Double lineTotal;
		private Double lineTotalIncludingVat;
		/** Preview only; absent from the JSON otherwise (the copies sent to the stores are unchanged). */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private Boolean missingPrice;

		/** A preview line of an item without a supply price. */
		public static Line missingPrice(String deliveryNumber, String itemCode, String itemName, int quantity) {
			Line line = new Line();
			line.deliveryNumber = deliveryNumber;
			line.itemCode = itemCode;
			line.itemName = itemName;
			line.quantity = quantity;
			line.missingPrice = Boolean.TRUE;
			return line;
		}

		public static Line of(HoSupplyInvoiceLine line) {
			Line copy = new Line();
			copy.lineNo = line.getLineNo();
			copy.deliveryNumber = line.getDeliveryNumber();
			copy.itemCode = line.getItemCode();
			copy.itemName = line.getItemName();
			copy.quantity = line.getQuantity();
			copy.unitPrice = line.getUnitPrice();
			copy.vatPercent = line.getVatPercent();
			copy.vatAmount = line.getVatAmount();
			copy.lineTotal = line.getLineTotal();
			copy.lineTotalIncludingVat = line.getLineTotalIncludingVat();
			return copy;
		}
	}
}
