package com.digithink.zsretail.headoffice.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.digithink.zsretail.core.dto.NavItemVariantsDTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fiches items (3.1.0), step 2: the item card of the head office page "Fiches items", one line of the list (a NAV item
 * number with every variant, active or not, or one item without NAV number) in one answer. The point de stock types are
 * the ERP items page's ({@link NavItemVariantsDTO.Point}, {@link NavItemVariantsDTO.PointPrice}), read by the same bean.
 */
@Data
@NoArgsConstructor
public class ItemCatalogueCardDTO {

	private Header header = new Header();

	/** By size order (none last), colour, choice, item code, as GET /nav-item/variants. */
	private List<Variant> variants = new ArrayList<>();

	/** The points with a row of one of the variants, in list order; empty when no point de stock exists. */
	private List<NavItemVariantsDTO.Point> stockPoints = new ArrayList<>();

	/** The read-only fields of the item form, from the highlighted variant, else the first one. */
	private Details details;

	@Data
	@NoArgsConstructor
	public static class Header {

		/** The NAV item number; null for a single item. */
		private String navItemNo;

		/** The single item's id; null for a NAV item. */
		private Long itemId;

		/** The NAV item number, or the single item's code. */
		private String code;

		private String name;

		/** True when one variant is active. */
		private boolean active;

		/** True when the card is one item without NAV number. */
		private boolean single;

		private String familyName;

		private String subFamilyName;

		private String categoryCode;

		private String categoryName;

		private String sectionCode;

		private String sectionName;

		/** Every variant, active or not. */
		private int variantsCount;

		private boolean withStock;

		private boolean withStockPoints;
	}

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

		private String seasonCode;

		private String divisionCode;

		private boolean active;

		/** PRODUCT, SERVICE or PACKAGE. */
		private String type;

		/** type PACKAGE, as the pack configuration reads it. */
		private Boolean isPackage;

		private Integer defaultVAT;

		/** Before VAT. */
		private Double unitPrice;

		/** Null where the head office keeps no stock of its own. */
		private BigDecimal stockQuantity;

		/** The active barcodes, by value. */
		private List<Barcode> barcodes = new ArrayList<>();

		/** True for the one variant whose item code or a barcode equals the search (trimmed, any case). */
		private boolean highlighted;

		/** Point id to the variant's row there; empty when no point de stock exists. */
		private Map<Long, NavItemVariantsDTO.PointPrice> prices = new LinkedHashMap<>();
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Barcode {

		private String barcode;

		private Boolean isPrimary;

		private String description;
	}

	@Data
	@NoArgsConstructor
	public static class Details {

		/** The variant these details come from. */
		private Long itemId;

		private String description;

		private String unitOfMeasure;

		private String category;

		private String brand;

		private String itemDiscGroup;

		private Double maximumAuthorizedDiscount;

		private Double costPrice;

		private boolean showInPos;

		/** The image's file name; null without an image. */
		private String imageUrl;

		/** HEAD_OFFICE when received from the head office catalogue copy; null when made here. */
		private String origin;
	}
}
