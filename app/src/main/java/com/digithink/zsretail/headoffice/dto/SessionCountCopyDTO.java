package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.digithink.zsretail.utils.WholeQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One cash count line of a {@link SessionCopyDTO} (task 2.2). */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionCountCopyDTO {

	/** 1, 2, 3... in the store's order. */
	private Integer lineNo;

	/** POS_USER or RESPONSIBLE. */
	private String counterType;

	/** Null for cash. */
	private String paymentMethodCode;

	private String paymentMethodName;

	private Double denominationValue;

	@JsonDeserialize(using = WholeQuantityDeserializer.class) // 2.2.1: whole only, 1.5 refused (400)
	private Integer quantity;

	private Double lineTotal;

	/** Cheque number, card digits... */
	private String referenceNumber;
}
