package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, task 6.4: one line of a price list. Written with itemCode (or itemId) and price; read with the item's
 * name and base price (item.unitPrice) beside.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PriceListLineDTO {

	private Long id;
	private Long itemId;
	private String itemCode;
	private String itemName;
	private Double basePrice;
	private Double price;
}
