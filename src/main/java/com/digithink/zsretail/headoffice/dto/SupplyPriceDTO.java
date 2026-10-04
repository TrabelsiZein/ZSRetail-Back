package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7B: the base supply price of an item as the supply prices page reads and writes it. On write,
 * the item by itemCode (or itemId) and the price (null deletes the base supply price).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SupplyPriceDTO {

	private Long itemId;
	private String itemCode;
	private String itemName;
	/** The base selling price (item.unitPrice), for comparison; read only. */
	private Double sellingPrice;
	/** Before VAT; null when the item has no base supply price. */
	private Double supplyPrice;
}
