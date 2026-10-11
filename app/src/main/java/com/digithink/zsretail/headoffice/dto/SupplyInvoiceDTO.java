package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7B: a supply invoice as the head office pages read it (list, detail, preview), and the request
 * that creates one ({storeId, deliveryIds, invoiceDate, note}). In the list, {@code lines} is null. A preview has no id
 * and no number, and names the items without a supply price in {@code missingPrices}.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SupplyInvoiceDTO {

	private Long id;
	private String invoiceNumber;
	private Long storeId;
	private String storeCode;
	private String storeName;
	/** yyyy-MM-dd; today when absent from a request. */
	private String invoiceDate;
	private Double subtotal;
	private Double taxAmount;
	private Double totalAmount;
	private String buyerName;
	private String buyerTaxNumber;
	private String buyerAddress;
	private String sellerName;
	private String sellerTaxNumber;
	private String sellerAddress;
	private String note;
	private Boolean paid;
	private LocalDate paidDate;
	private String paidNote;
	private List<String> deliveryNumbers = new ArrayList<>();
	private List<SupplyInvoiceCopyDTO.Line> lines;
	/** Preview only: the item codes without a supply price for this store. */
	private List<String> missingPrices;
	/** Request only: the BLs to invoice (ho_delivery.id). */
	private List<Long> deliveryIds;
}
