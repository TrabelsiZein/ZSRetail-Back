package com.digithink.zsretail.headoffice.dto;

import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 6: a barcode as it travels down (record BARCODE:&lt;barcode&gt;), with its item by code. Active
 * only when the barcode and its item are active: a scan at the till does not check the item, so the barcode of an item
 * deactivated at the head office is sent inactive.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogueBarcodeCopyDTO {

	private String kind = CatalogueKind.BARCODE.name();
	private String barcode;
	private String itemCode;
	private String description;
	private Boolean isPrimary;
	private Boolean active;

	/** The copy as the head office sends it: active = barcode active and item active. */
	public static CatalogueBarcodeCopyDTO of(ItemBarcode barcode) {
		CatalogueBarcodeCopyDTO copy = new CatalogueBarcodeCopyDTO();
		copy.barcode = barcode.getBarcode();
		copy.itemCode = barcode.getItem() == null ? null : barcode.getItem().getItemCode();
		copy.description = barcode.getDescription();
		copy.isPrimary = Boolean.TRUE.equals(barcode.getIsPrimary());
		copy.active = !Boolean.FALSE.equals(barcode.getActive())
				&& (barcode.getItem() == null || !Boolean.FALSE.equals(barcode.getItem().getActive()));
		return copy;
	}
}
