package com.digithink.zsretail.core.erp.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.digithink.zsretail.core.erp.service.ItemVariant;
import com.digithink.zsretail.erp.api.ErpInvoiceStart;

/**
 * ERP catalogue, step 6: what the head office tables hold, read only, for the comparison with the ERP. Only the fields
 * the import (ErpItemBootstrapService) applies. Keys are the codes (item code, family code, barcode value). The JPA
 * queries ({@link JpaHeadOfficeCatalogue}), or maps in the tests.
 * Code cleanup step 3: was NavPosPagesHeadOffice of the navpospages connector, now shared by the catalogue engine.
 */
public interface HeadOfficeCatalogue extends ErpInvoiceStart {

	Map<String, Family> families();

	Map<String, SubFamily> subFamilies();

	Map<String, Item> items();

	/** The barcodes among these values that the head office has, with their item. */
	Map<String, Barcode> barcodes(Collection<String> values);

	/** General setup TAX_STAMP_ERP_ITEM_CODE: the ERP item the import never saves (the local TAX_STAMP is used); or null. */
	String taxStampErpCode();

	/** Release 2.2: the General Setup code of "Last invoice read" (read only, written by the invoice import). */
	String INVOICES_LAST_READ = "ERP_INVOICES_LAST_READ";

	/** Release 2.2: General Setup ERP_INVOICES_READ_AFTER, trimmed; null when empty. */
	@Override
	default String invoicesReadAfter() {
		return null;
	}

	/** Item variants (ZEN), step 4: the catégories, by code; none by default (a head office read without them). */
	default Map<String, Level> categories() {
		return new LinkedHashMap<>();
	}

	/** Item variants (ZEN), step 4: the sections, by code; none by default. */
	default Map<String, Level> sections() {
		return new LinkedHashMap<>();
	}

	/** A family or a sub-family as the head office has it. */
	class Family {
		public final String code;
		public final String name;
		public final String description;
		public final Boolean active;
		public final String erpExternalId;
		/** Item variants (ZEN), step 4: item_family.category_code (a family only; null for a sub-family). */
		public final String categoryCode;

		public Family(String code, String name, String description, Boolean active, String erpExternalId) {
			this(code, name, description, active, erpExternalId, null);
		}

		public Family(String code, String name, String description, Boolean active, String erpExternalId,
				String categoryCode) {
			this.code = code;
			this.name = name;
			this.description = description;
			this.active = active;
			this.erpExternalId = erpExternalId;
			this.categoryCode = categoryCode;
		}
	}

	/**
	 * Item variants (ZEN), step 4: a catégorie (above the families) or a section (below the sub-families) as the head
	 * office has it; parentCode is a section's sub-family code, null for a catégorie.
	 */
	class Level {
		public final String code;
		public final String name;
		public final String description;
		public final Integer displayOrder;
		public final Boolean active;
		public final String erpExternalId;
		public final String parentCode;

		public Level(String code, String name, String description, Integer displayOrder, Boolean active,
				String erpExternalId, String parentCode) {
			this.code = code;
			this.name = name;
			this.description = description;
			this.displayOrder = displayOrder;
			this.active = active;
			this.erpExternalId = erpExternalId;
			this.parentCode = parentCode;
		}
	}

	class SubFamily extends Family {
		public final String familyCode;

		public SubFamily(String code, String name, String description, Boolean active, String erpExternalId,
				String familyCode) {
			super(code, name, description, active, erpExternalId);
			this.familyCode = familyCode;
		}
	}

	/** An item as the head office has it. erpExternalId is set only on an item the ERP import saved. */
	class Item {
		public final String code;
		public final String name;
		public final String description;
		public final Double unitPrice;
		public final Integer defaultVat;
		public final Boolean active;
		public final String erpExternalId;
		public final String itemDiscGroup;
		public final Double maximumAuthorizedDiscount;
		public final String familyCode;
		public final String subFamilyCode;
		/** Item variants (ZEN), step 3: the 14 variant fields; {@link ItemVariant#NONE} for an item without. */
		public final ItemVariant variant;
		/** Item variants (ZEN), step 3: item.brand (written by the import only when the ERP gives one). */
		public final String brand;

		/** An item without variant fields and without brand. */
		public Item(String code, String name, String description, Double unitPrice, Integer defaultVat, Boolean active,
				String erpExternalId, String itemDiscGroup, Double maximumAuthorizedDiscount, String familyCode,
				String subFamilyCode) {
			this(code, name, description, unitPrice, defaultVat, active, erpExternalId, itemDiscGroup,
					maximumAuthorizedDiscount, familyCode, subFamilyCode, ItemVariant.NONE, null);
		}

		public Item(String code, String name, String description, Double unitPrice, Integer defaultVat, Boolean active,
				String erpExternalId, String itemDiscGroup, Double maximumAuthorizedDiscount, String familyCode,
				String subFamilyCode, ItemVariant variant, String brand) {
			this.code = code;
			this.name = name;
			this.description = description;
			this.unitPrice = unitPrice;
			this.defaultVat = defaultVat;
			this.active = active;
			this.erpExternalId = erpExternalId;
			this.itemDiscGroup = itemDiscGroup;
			this.maximumAuthorizedDiscount = maximumAuthorizedDiscount;
			this.familyCode = familyCode;
			this.subFamilyCode = subFamilyCode;
			this.variant = variant == null ? ItemVariant.NONE : variant;
			this.brand = brand;
		}

		/** Came from the ERP: only the ERP import sets erp_external_id on an item. */
		public boolean fromErp() {
			return erpExternalId != null && !erpExternalId.trim().isEmpty();
		}

		/** Active unless explicitly false (the column defaults to true). */
		public boolean isActive() {
			return !Boolean.FALSE.equals(active);
		}
	}

	/** A barcode of the head office and the code of its item. */
	class Barcode {
		public final String barcode;
		public final String itemCode;
		public final Boolean active;

		public Barcode(String barcode, String itemCode, Boolean active) {
			this.barcode = barcode;
			this.itemCode = itemCode;
			this.active = active;
		}
	}
}
