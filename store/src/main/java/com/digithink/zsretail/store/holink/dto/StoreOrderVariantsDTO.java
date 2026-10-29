package com.digithink.zsretail.store.holink.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.digithink.zsretail.core.dto.ItemImageDTO;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1: the active variants of one NAV item number at the store with the live NAV stock of each at the store's location
 * (GET /store-order/variants), answered whole or not at all (503 without the head office). See
 * docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
public class StoreOrderVariantsDTO {

	private String navItemNo;

	private String name;

	private List<Variant> variants = new ArrayList<>();

	/** 3.1.1: the draft's colis of this item; null: none. */
	private Integer draftColis;

	/** 3.1.1: the colis of the item the store may order (ZEN's colisDisponibles); null: no colis data. */
	private Integer colisAvailable;

	/** 3.1.1: the pieces of one colis (the sum of the variants' qtyPerColis); null: no colis data. */
	private Integer colisContent;

	@Data
	@NoArgsConstructor
	public static class Variant {
		private Long itemId;
		private String itemCode;
		private String variantCode;
		private String variantDescription;
		private String sizeCode;
		private String sizeDescription;
		private String colorCode;
		private String colorDescription;
		private String choiceCode;
		private String choiceDescription;
		/** The public price before VAT (item.publicPrice, else its unitPrice). */
		private Double price;
		/** The VAT rate of the price (percent). */
		private Integer vatRate;
		/** item.imageUrl, the main image. */
		private String mainImage;
		/** The item's images (item_image), the main one first. */
		private List<ItemImageDTO> images = new ArrayList<>();
		/** NAV's stock at the store's location; null when NAV gives no row for the variant (the screen shows "—"). */
		private BigDecimal stock;
		/** The connector's information on the variant, label → value in order (the cell's tooltip); null without. */
		private LinkedHashMap<String, String> details;
		/** The ERP's order of the variant's size (the order of the size columns); null without. */
		private Integer sizeOrder;
		/** 3.1.1: the variant's pieces in one colis (ZEN's qteSerieType); null: no colis data. */
		private Integer qtyPerColis;
		/** 3.1.1: the variant may be ordered by pieces (ZEN's vracAutorise); null: no rule. */
		private Boolean vracAllowed;
		/** The quantity of this variant in the store's draft; null when it has no line. */
		private BigDecimal draftQuantity;
	}
}
