package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.digithink.zsretail.utils.WholeQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

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

	@JsonDeserialize(using = WholeQuantityDeserializer.class) // 2.2.1: whole only, 1.5 refused (400)
	private Integer quantity;

	/** Excluding VAT. */
	private Double unitPrice;

	private Double unitPriceIncludingVat;

	/** Excluding VAT. */
	private Double lineTotal;

	private Double lineTotalIncludingVat;

	private String notes;
}
