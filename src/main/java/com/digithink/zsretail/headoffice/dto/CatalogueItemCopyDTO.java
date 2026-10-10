package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemComposition;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.digithink.zsretail.utils.WholeQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 6: an item as it travels down (record ITEM:&lt;code&gt;), by codes only, with the one selling
 * price worked out for the store that pulls (its price list's line, otherwise the base price). Never: stock, minimum
 * stock, cost fields, ERP id, image. A pack carries its components by item code.
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
		return copy;
	}
}
