package com.digithink.zsretail.core.erp.service;

import java.util.Objects;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * Item variants (ZEN), step 3: the 14 variant fields of an item (item.nav_item_no ... item.section_code), in one place:
 * what the ERP import writes ({@link #applyTo(Item)}, always, null when the ERP gives none), what the catalogue engine
 * compares (equals) and what its copies of a head office item keep ({@link #copyTo(ErpItemDTO)}). Texts are trimmed,
 * blank as null, so that "" and null are the same value. All null ({@link #NONE}) for an ERP that gives no variant. The
 * brand is not here: it is written only when the ERP gives one.
 */
public final class ItemVariant {

	/** No variant: every field null. */
	public static final ItemVariant NONE = new ItemVariant(null, null, null, null, null, null, null, null, null, null, null,
			null, null, null);

	public final String navItemNo;
	public final String variantCode;
	public final String variantDescription;
	public final String sizeCode;
	public final String sizeDescription;
	public final Integer sizeSortOrder;
	public final String colorCode;
	public final String colorDescription;
	public final String choiceCode;
	public final String choiceDescription;
	public final String seasonCode;
	public final String divisionCode;
	public final String categoryCode;
	public final String sectionCode;

	public ItemVariant(String navItemNo, String variantCode, String variantDescription, String sizeCode,
			String sizeDescription, Integer sizeSortOrder, String colorCode, String colorDescription, String choiceCode,
			String choiceDescription, String seasonCode, String divisionCode, String categoryCode, String sectionCode) {
		this.navItemNo = clean(navItemNo);
		this.variantCode = clean(variantCode);
		this.variantDescription = clean(variantDescription);
		this.sizeCode = clean(sizeCode);
		this.sizeDescription = clean(sizeDescription);
		this.sizeSortOrder = sizeSortOrder;
		this.colorCode = clean(colorCode);
		this.colorDescription = clean(colorDescription);
		this.choiceCode = clean(choiceCode);
		this.choiceDescription = clean(choiceDescription);
		this.seasonCode = clean(seasonCode);
		this.divisionCode = clean(divisionCode);
		this.categoryCode = clean(categoryCode);
		this.sectionCode = clean(sectionCode);
	}

	/** The variant fields of an item of the ERP. */
	public static ItemVariant of(ErpItemDTO item) {
		return new ItemVariant(item.getNavItemNo(), item.getVariantCode(), item.getVariantDescription(), item.getSizeCode(),
				item.getSizeDescription(), item.getSizeSortOrder(), item.getColorCode(), item.getColorDescription(),
				item.getChoiceCode(), item.getChoiceDescription(), item.getSeasonCode(), item.getDivisionCode(),
				item.getCategoryCode(), item.getSectionCode());
	}

	/** Writes the 14 fields into the item, null ones included (the import). */
	public void applyTo(Item item) {
		item.setNavItemNo(navItemNo);
		item.setVariantCode(variantCode);
		item.setVariantDescription(variantDescription);
		item.setSizeCode(sizeCode);
		item.setSizeDescription(sizeDescription);
		item.setSizeSortOrder(sizeSortOrder);
		item.setColorCode(colorCode);
		item.setColorDescription(colorDescription);
		item.setChoiceCode(choiceCode);
		item.setChoiceDescription(choiceDescription);
		item.setSeasonCode(seasonCode);
		item.setDivisionCode(divisionCode);
		item.setCategoryCode(categoryCode);
		item.setSectionCode(sectionCode);
	}

	/** Writes the 14 fields into an item of the ERP (the engine's copies of a head office item). */
	public void copyTo(ErpItemDTO item) {
		item.setNavItemNo(navItemNo);
		item.setVariantCode(variantCode);
		item.setVariantDescription(variantDescription);
		item.setSizeCode(sizeCode);
		item.setSizeDescription(sizeDescription);
		item.setSizeSortOrder(sizeSortOrder);
		item.setColorCode(colorCode);
		item.setColorDescription(colorDescription);
		item.setChoiceCode(choiceCode);
		item.setChoiceDescription(choiceDescription);
		item.setSeasonCode(seasonCode);
		item.setDivisionCode(divisionCode);
		item.setCategoryCode(categoryCode);
		item.setSectionCode(sectionCode);
	}

	/** Trimmed, blank as null. */
	static String clean(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ItemVariant)) {
			return false;
		}
		ItemVariant that = (ItemVariant) other;
		return Objects.equals(navItemNo, that.navItemNo) && Objects.equals(variantCode, that.variantCode)
				&& Objects.equals(variantDescription, that.variantDescription) && Objects.equals(sizeCode, that.sizeCode)
				&& Objects.equals(sizeDescription, that.sizeDescription) && Objects.equals(sizeSortOrder, that.sizeSortOrder)
				&& Objects.equals(colorCode, that.colorCode) && Objects.equals(colorDescription, that.colorDescription)
				&& Objects.equals(choiceCode, that.choiceCode) && Objects.equals(choiceDescription, that.choiceDescription)
				&& Objects.equals(seasonCode, that.seasonCode) && Objects.equals(divisionCode, that.divisionCode)
				&& Objects.equals(categoryCode, that.categoryCode) && Objects.equals(sectionCode, that.sectionCode);
	}

	@Override
	public int hashCode() {
		return Objects.hash(navItemNo, variantCode, variantDescription, sizeCode, sizeDescription, sizeSortOrder, colorCode,
				colorDescription, choiceCode, choiceDescription, seasonCode, divisionCode, categoryCode, sectionCode);
	}

	/** The fields that are set, for a test failure or a log line. */
	@Override
	public String toString() {
		StringBuilder text = new StringBuilder("variant {");
		append(text, "navItemNo", navItemNo);
		append(text, "variantCode", variantCode);
		append(text, "variantDescription", variantDescription);
		append(text, "sizeCode", sizeCode);
		append(text, "sizeDescription", sizeDescription);
		append(text, "sizeSortOrder", sizeSortOrder);
		append(text, "colorCode", colorCode);
		append(text, "colorDescription", colorDescription);
		append(text, "choiceCode", choiceCode);
		append(text, "choiceDescription", choiceDescription);
		append(text, "seasonCode", seasonCode);
		append(text, "divisionCode", divisionCode);
		append(text, "categoryCode", categoryCode);
		append(text, "sectionCode", sectionCode);
		return text.append('}').toString();
	}

	private static void append(StringBuilder text, String name, Object value) {
		if (value != null) {
			text.append(text.charAt(text.length() - 1) == '{' ? "" : ", ").append(name).append('=').append(value);
		}
	}
}
