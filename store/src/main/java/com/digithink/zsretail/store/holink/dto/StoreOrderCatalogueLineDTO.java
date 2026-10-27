package com.digithink.zsretail.store.holink.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1: one line of the order catalogue (GET /store-order/catalogue), one NAV item number with its active variants.
 * See docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
public class StoreOrderCatalogueLineDTO {

	private String navItemNo;

	private String name;

	/** The first variant's image (item.imageUrl, the first variant that has one, in the variants' order); null without. */
	private String mainImage;

	/** The active variants. */
	private long variantCount;

	/** The first variant's public price before VAT (item.publicPrice, else its unitPrice). */
	private Double price;

	/** The VAT rate of the price (percent), the first variant's item.defaultVAT. */
	private Integer vatRate;

	private String familyName;
	private String subFamilyName;
	private String categoryCode;
	private String categoryName;
	private String sectionCode;
	private String sectionName;
}
