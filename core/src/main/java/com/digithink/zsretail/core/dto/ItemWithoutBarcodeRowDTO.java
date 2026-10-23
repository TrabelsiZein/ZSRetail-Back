package com.digithink.zsretail.core.dto;

import com.digithink.zsretail.core.model.enumeration.RecordOrigin;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** One item without any active barcode (GET item-barcode/items-without-barcode), built by the query itself. */
@Getter
@AllArgsConstructor
public class ItemWithoutBarcodeRowDTO {

	private Long itemId;
	private String itemCode;
	private String itemName;
	private Boolean itemActive;
	private RecordOrigin origin;
	private String familyName;
	private String subFamilyName;
	/** Classification column (3.1.0): the item's catégorie and section codes (ZEN); null on a customer without them. */
	private String categoryCode;
	private String sectionCode;
}
