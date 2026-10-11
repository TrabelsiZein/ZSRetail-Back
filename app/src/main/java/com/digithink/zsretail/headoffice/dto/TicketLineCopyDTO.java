package com.digithink.zsretail.headoffice.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One line of a {@link TicketCopyDTO} (task 2.2). Amounts as stored by the store. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TicketLineCopyDTO {

	/** 1, 2, 3... in the store's line order. */
	private Integer lineNo;

	private String itemCode;

	/** Item name at the store when the copy is sent. */
	private String itemName;

	/** 2.2.1: up to 3 decimals, written without trailing zeros (2, 0.2); a 2.2.0 head office reads 0.2 as 0. */
	private BigDecimal quantity;

	/** Excluding VAT. */
	private Double unitPrice;

	private Double unitPriceIncludingVat;

	private Integer vatPercent;

	private Double vatAmount;

	private Double discountPercentage;

	private Double discountAmount;

	/** MANUAL, SALES_PRICE, SALES_DISCOUNT or PROMOTION; null when no discount. */
	private String discountSource;

	private String promotionCode;

	/** Excluding VAT. */
	private Double lineTotal;

	private Double lineTotalIncludingVat;
}
