package com.digithink.zsretail.headoffice.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One line of a {@link ReturnCopyDTO} (task 2.2). */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReturnLineCopyDTO {

	/** 1, 2, 3... in the store's line order. */
	private Integer lineNo;

	private String itemCode;

	private String itemName;

	/** 2.2.2: up to 3 decimals, written without trailing zeros (2, 0.2), so a whole return keeps the copy hash of 2.2.1. */
	private BigDecimal quantity;

	/** Excluding VAT. */
	private Double unitPrice;

	private Double unitPriceIncludingVat;

	/** Excluding VAT. */
	private Double lineTotal;

	private Double lineTotalIncludingVat;

	private String notes;
}
