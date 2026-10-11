package com.digithink.zsretail.dto;

import com.digithink.zsretail.model.enumeration.RecordOrigin;

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
}
