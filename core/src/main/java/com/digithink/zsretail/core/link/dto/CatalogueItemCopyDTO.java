package com.digithink.zsretail.core.link.dto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemComposition;
import com.digithink.zsretail.core.model.ItemImage;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.core.model.enumeration.ImageSource;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.digithink.zsretail.core.utils.WholeQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 6: an item as it travels down (record ITEM:&lt;code&gt;), by codes only, with the one selling
 * price worked out for the store that pulls (its price list's line, otherwise the base price). Never: stock, minimum
 * stock, cost fields, ERP id, image file (Item.imageUrl); 3.1.1: the images given by address (item_image URL) travel.
 * A pack carries its components by item code.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogueItemCopyDTO {

	private String kind = CatalogueKind.ITEM.name();
	private String itemCode;
	private String name;
	private String description;
	private ItemType type;

	/** The selling price for this store (same meaning as Item.unitPrice). */
	private Double unitPrice;
	private Integer defaultVAT;
	private String unitOfMeasure;
	private String category;
	private String brand;
	private String itemDiscGroup;
	private Double maximumAuthorizedDiscount;
	private Boolean showInPos;

	/** The old single barcode field of the item (Item.barcode). */
	private String barcode;
	private String familyCode;
	private String subFamilyCode;

	/** Active components of a pack, sorted by item code; empty for another item. */
	private List<Component> components = new ArrayList<>();
	private Boolean active;

	// Item variants (ZEN), step 7: the 14 variant fields of the item, as the head office has them (null for an item
	// without variant). A store of an older build ignores them (unknown properties are ignored).
	private String navItemNo;
	private String variantCode;
	private String variantDescription;
	private String sizeCode;
	private String sizeDescription;
	private Integer sizeSortOrder;
	private String colorCode;
	private String colorDescription;
	private String choiceCode;
	private String choiceDescription;
	private String seasonCode;
	private String divisionCode;
	/** ItemCategory.code, by code only (the store never waits for it). */
	private String categoryCode;
	/** ItemSection.code, by code only (the store never waits for it). */
	private String sectionCode;

	// Item variants (ZEN), clearance (2026-10-17): set only while the store's point sells the item at its clearance
	// price (unitPrice is then the clearance price): clearance true and the point's public price before VAT, shown
	// struck through at the till. Null otherwise (EMTOP, Happyness, a hand-made item). An older store ignores them.
	private Boolean clearance;
	private Double publicPrice;

	/**
	 * 3.1.1 (item images): the item's images given by address (item_image source URL), in order, the main one first;
	 * empty when it has none. Null: not sent (a head office of an older build): the store keeps its rows. A store of an
	 * older build ignores it. Files (FILE) never travel.
	 */
	private List<Image> images;

	/** One image given by address. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Image {
		private String url;
		private boolean main;
		private int sort;
	}

	/** The URL images of an item (its rows in their order, FILE ones left out), as they travel. */
	public static List<Image> imagesOf(List<ItemImage> rows) {
		List<Image> images = new ArrayList<>();
		if (rows != null) {
			for (ItemImage row : rows) {
				if (row.getSource() == ImageSource.URL) {
					images.add(new Image(row.getPath(), row.isMain(), row.getSortOrder()));
				}
			}
		}
		return images;
	}

	/** One component of a pack. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Component {
		private String itemCode;
		@JsonDeserialize(using = WholeQuantityDeserializer.class) // 2.2.1: whole only, 1.5 refused (400)
		private Integer quantity;
	}

	/** The copy of an item at this price, with its active components (null or empty: none). */
	public static CatalogueItemCopyDTO of(Item item, Double price, List<ItemComposition> compositions) {
		CatalogueItemCopyDTO copy = new CatalogueItemCopyDTO();
		copy.itemCode = item.getItemCode();
		copy.name = item.getName();
		copy.description = item.getDescription();
		copy.type = item.getType();
		copy.unitPrice = price;
		copy.defaultVAT = item.getDefaultVAT();
		copy.unitOfMeasure = item.getUnitOfMeasure();
		copy.category = item.getCategory();
		copy.brand = item.getBrand();
		copy.itemDiscGroup = item.getItemDiscGroup();
		copy.maximumAuthorizedDiscount = item.getMaximumAuthorizedDiscount();
		copy.showInPos = !Boolean.FALSE.equals(item.getShowInPos());
		copy.barcode = item.getBarcode();
		copy.familyCode = item.getItemFamily() == null ? null : item.getItemFamily().getCode();
		copy.subFamilyCode = item.getItemSubFamily() == null ? null : item.getItemSubFamily().getCode();
		if (compositions != null) {
			for (ItemComposition composition : compositions) {
				if (!Boolean.FALSE.equals(composition.getActive()) && composition.getComponentItem() != null) {
					copy.components.add(new Component(composition.getComponentItem().getItemCode(), composition.getQuantity()));
				}
			}
			copy.components.sort(Comparator.comparing(Component::getItemCode));
		}
		copy.active = !Boolean.FALSE.equals(item.getActive());
		copy.navItemNo = item.getNavItemNo();
		copy.variantCode = item.getVariantCode();
		copy.variantDescription = item.getVariantDescription();
		copy.sizeCode = item.getSizeCode();
		copy.sizeDescription = item.getSizeDescription();
		copy.sizeSortOrder = item.getSizeSortOrder();
		copy.colorCode = item.getColorCode();
		copy.colorDescription = item.getColorDescription();
		copy.choiceCode = item.getChoiceCode();
		copy.choiceDescription = item.getChoiceDescription();
		copy.seasonCode = item.getSeasonCode();
		copy.divisionCode = item.getDivisionCode();
		copy.categoryCode = item.getCategoryCode();
		copy.sectionCode = item.getSectionCode();
		copy.clearance = item.getClearance();
		copy.publicPrice = item.getPublicPrice();
		return copy;
	}
}
