package com.digithink.zsretail.connector.parafendri.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Step 6: one ticket as the body of POST companies(id)/salesOrders, the header and its lines (salesOrderLines, a
 * contained navigation property: inserted in the same POST). orderStatus is never sent: Business Central sets it.
 */
@Getter
@Setter
@NoArgsConstructor
public class ParafendriSalesOrder {

	@JsonProperty("ticketNo")
	private String ticketNo;
	@JsonProperty("customerNo")
	private String customerNo;
	@JsonProperty("locationCode")
	private String locationCode;
	/** yyyy-MM-dd */
	@JsonProperty("orderDate")
	private String orderDate;
	/** yyyy-MM-dd */
	@JsonProperty("postingDate")
	private String postingDate;
	@JsonProperty("ticketAmount")
	private BigDecimal ticketAmount;
	@JsonProperty("amountIncludingVAT")
	private BigDecimal amountIncludingVAT;
	@JsonProperty("discountPercent")
	private BigDecimal discountPercent;
	@JsonProperty("invoiceDiscountAmount")
	private BigDecimal invoiceDiscountAmount;
	@JsonProperty("invoiceDiscountPercent")
	private BigDecimal invoiceDiscountPercent;
	@JsonProperty("fenceNo")
	private String fenceNo;
	@JsonProperty("salesOrderLines")
	private List<Line> salesOrderLines = new ArrayList<>();

	/** One line of the order. */
	@Getter
	@Setter
	@NoArgsConstructor
	public static class Line {

		@JsonProperty("lineNo")
		private Integer lineNo;
		@JsonProperty("itemNo")
		private String itemNo;
		@JsonProperty("quantity")
		private BigDecimal quantity;
		@JsonProperty("unitPrice")
		private BigDecimal unitPrice;
		@JsonProperty("lineDiscountPercent")
		private BigDecimal lineDiscountPercent;
		@JsonProperty("promotionNo")
		private String promotionNo;
		@JsonProperty("amountIncludingVAT")
		private BigDecimal amountIncludingVAT;
	}
}
