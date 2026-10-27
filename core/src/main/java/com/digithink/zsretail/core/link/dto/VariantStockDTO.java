package com.digithink.zsretail.core.link.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1 (store orders): the live NAV stock of one variant for the asking store, one row of GET
 * /ho/orders/variant-stock?navItemNo= (the head office reads the store from X-Store-Code). details and sizeOrder are
 * optional and only informational, as the connector gave them (null otherwise). See docs/modules/store-orders.md.
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

	/** Label → value, in the order to show them (the tooltip of the variant's cell); null when there are none. */
	private LinkedHashMap<String, String> details;

	/** The ERP's order of the variant's size (the order of the size columns); null when there is none. */
	private Integer sizeOrder;

	public VariantStockDTO(String itemNo, String variantCode, BigDecimal quantity) {
		this(itemNo, variantCode, quantity, null, null);
	}
}
