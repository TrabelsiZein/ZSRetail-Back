package com.digithink.zsretail.erp.navpospages.sync;

import java.util.Collection;
import java.util.Map;

/**
 * ERP catalogue, step 6: what the head office tables hold, read only, for the comparison with the ERP. Only the fields
 * the import (ErpItemBootstrapService) applies. Keys are the codes (item code, family code, barcode value). The JPA
 * queries ({@link JpaNavPosPagesHeadOffice}), or maps in the tests.
 */
public interface NavPosPagesHeadOffice {

	Map<String, Family> families();

	Map<String, SubFamily> subFamilies();

	Map<String, Item> items();

	/** The barcodes among these values that the head office has, with their item. */
	Map<String, Barcode> barcodes(Collection<String> values);

	/** General setup TAX_STAMP_ERP_ITEM_CODE: the ERP item the import never saves (the local TAX_STAMP is used); or null. */
	String taxStampErpCode();

	/** Release 2.2: the General Setup code of "Read ERP invoices after number" (empty: every invoice of the page). */
	String INVOICES_READ_AFTER = "ERP_INVOICES_READ_AFTER";

	/** Release 2.2: the General Setup code of "Last invoice read" (read only, written by the invoice import). */
	String INVOICES_LAST_READ = "ERP_INVOICES_LAST_READ";

	/** Release 2.2: General Setup ERP_INVOICES_READ_AFTER, trimmed; null when empty. */
	default String invoicesReadAfter() {
		return null;
	}

	/** A family or a sub-family as the head office has it. */
	class Family {
		public final String code;
		public final String name;
		public final String description;
		public final Boolean active;
		public final String erpExternalId;

		public Family(String code, String name, String description, Boolean active, String erpExternalId) {
			this.code = code;
			this.name = name;
			this.description = description;
			this.active = active;
			this.erpExternalId = erpExternalId;
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

		public Item(String code, String name, String description, Double unitPrice, Integer defaultVat, Boolean active,
				String erpExternalId, String itemDiscGroup, Double maximumAuthorizedDiscount, String familyCode,
				String subFamilyCode) {
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
