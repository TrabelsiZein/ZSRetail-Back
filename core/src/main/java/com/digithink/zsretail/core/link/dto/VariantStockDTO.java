package com.digithink.zsretail.core.link.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1 (store orders): the live NAV stock of one variant at the asking store's location, one row of GET
 * /ho/orders/variant-stock?navItemNo= (the head office reads the location from X-Store-Code). See
 * docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VariantStockDTO {

	/** The ERP item number. */
	private String itemNo;

	/** The ERP variant code; null or empty for an item without variants. */
	private String variantCode;

	private BigDecimal quantity;
}
