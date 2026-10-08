package com.digithink.zsretail.headoffice.dto;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.digithink.zsretail.headoffice.model.HoErpInvoice;
import com.digithink.zsretail.headoffice.model.HoErpInvoiceLine;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Invoices from the ERP, step (c): an invoice of the ERP as it travels down to its store only (domain SUPPLY, record
 * ERPINV:&lt;number&gt;, kind ERP_INVOICE), by codes, never a database id. The ERP number is invoiceNumber, never "number":
 * a store that does not know this kind reads no BL number in it and refuses the record. Dates as ISO strings. The store
 * takes its stock and cost from it and writes its purchase invoice when it receives it.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ErpInvoiceCopyDTO {

	public static final String KIND = "ERP_INVOICE";
	public static final String RECORD_PREFIX = "ERPINV:";

	private String kind = KIND;
	private String invoiceNumber;
	/** yyyy-MM-dd. */
	private String documentDate;
	/** yyyy-MM-dd. */
	private String postingDate;
	/** The seller named on the store's purchase invoice (erp.navpospages.invoices.seller-name). */
	private String sellerName;
	private String customerNo;
	private String customerName;
	/** yyyy-MM-ddTHH:mm:ss, head office clock when the store was found. */
	private String sentAt;
	private Double totalExclVat;
	private Double totalVat;
	private Double totalInclVat;
	private List<Line> lines = new ArrayList<>();

	public static String recordCode(String number) {
		return RECORD_PREFIX + number;
	}

	/** The ERP number of a record code; null when it is not one. */
	public static String numberOf(String recordCode) {
		return recordCode != null && recordCode.startsWith(RECORD_PREFIX) && recordCode.length() > RECORD_PREFIX.length()
				? recordCode.substring(RECORD_PREFIX.length())
				: null;
	}

	public static ErpInvoiceCopyDTO of(HoErpInvoice invoice, String sellerName) {
		ErpInvoiceCopyDTO copy = new ErpInvoiceCopyDTO();
		copy.invoiceNumber = invoice.getBcNumber();
		copy.documentDate = invoice.getDocumentDate() == null ? null : invoice.getDocumentDate().toString();
		copy.postingDate = invoice.getPostingDate() == null ? null : invoice.getPostingDate().toString();
		copy.sellerName = sellerName;
		copy.customerNo = invoice.getCustomerNo();
		copy.customerName = invoice.getCustomerName();
		copy.sentAt = invoice.getSentAt() == null ? null : invoice.getSentAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		copy.totalExclVat = invoice.getTotalExclVat();
		copy.totalVat = invoice.getTotalVat();
		copy.totalInclVat = invoice.getTotalInclVat();
		for (HoErpInvoiceLine line : invoice.getLines()) {
			Line row = new Line();
			row.setLineNo(line.getLineNo());
			row.setLineType(line.getLineType() == null ? null : line.getLineType().name());
			row.setItemCode(line.getItemCode());
			row.setDescription(line.getDescription());
			row.setQuantity(line.getQuantity());
			row.setUnitPrice(line.getUnitPrice());
			row.setLineDiscountPercent(line.getLineDiscountPercent());
			row.setLineAmount(line.getLineAmount());
			row.setUnitCost(line.getUnitCost());
			copy.lines.add(row);
		}
		copy.lines.sort((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()));
		return copy;
	}

	/** One line: ITEM (an item: stock and cost at the store) or OTHER (an amount without item: no stock, no cost). */
	@Data
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Line {
		private Integer lineNo;
		/** ITEM or OTHER. */
		private String lineType;
		/** The ERP item number; null on an OTHER line. */
		private String itemCode;
		private String description;
		/** The quantity invoiced (whole: a held invoice is never sent). */
		private Integer quantity;
		private Double unitPrice;
		private Double lineDiscountPercent;
		/** After the line discount, before the VAT. */
		private Double lineAmount;
		/** lineAmount / quantity; null at amount 0 and on an OTHER line. */
		private Double unitCost;
	}
}
