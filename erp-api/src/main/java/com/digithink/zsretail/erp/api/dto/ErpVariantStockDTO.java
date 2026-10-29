package com.digithink.zsretail.erp.api.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1 (store orders): the ERP's stock of one variant for one store (ErpConnector.fetchVariantStock). details and
 * sizeOrder are optional, only informational: a connector fills what its ERP gives, the others leave them null.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErpVariantStockDTO {

	private String itemNo;

	/** Empty for an item without variants. */
	private String variantCode;

	/** Null when the ERP gives none (unknown, never taken as 0). */
	private BigDecimal quantity;

	/** Label → value, in the order to show them (a tooltip of the store's screen); null when the connector gives none. */
	private LinkedHashMap<String, String> details;

	/** The ERP's order of the variant's size (the size columns of the store's screen); null when it gives none. */
	private Integer sizeOrder;

	/**
	 * 3.1.1, colis: the pieces of this variant in one colis (ZEN's qteSerieType: a colis is one série of every colour of
	 * the item); null when the connector gives none (no colis).
	 */
	private Integer qtyPerColis;

	/** 3.1.1: the variant may be ordered by pieces (ZEN's vracAutorise); null when the connector gives none (no rule). */
	private Boolean vracAllowed;

	/** 3.1.1: the colis of the item the store may order (ZEN's colisDisponibles, the same on every row); null: none given. */
	private Integer colisAvailable;

	public ErpVariantStockDTO(String itemNo, String variantCode, BigDecimal quantity) {
		this(itemNo, variantCode, quantity, null, null);
	}

	public ErpVariantStockDTO(String itemNo, String variantCode, BigDecimal quantity,
			LinkedHashMap<String, String> details, Integer sizeOrder) {
		this(itemNo, variantCode, quantity, details, sizeOrder, null, null, null);
	}
}
