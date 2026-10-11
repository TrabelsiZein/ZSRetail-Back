package com.digithink.zsretail.dto;

import com.digithink.zsretail.model.enumeration.RecordOrigin;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One row of the barcodes page (GET item-barcode/list): a barcode with its item, family and sub-family. Built by the
 * query itself (ItemBarcodeRepository.BARCODE_ROWS), one query with joins, no query per row.
 */
@Getter
@AllArgsConstructor
public class ItemBarcodeRowDTO {

	private Long id;
	private String barcode;
	private Boolean isPrimary;
	private String description;
	/** The barcode's own flag (null counts as active). */
	private Boolean active;
	private Long itemId;
	private String itemCode;
	private String itemName;
	/** The item's flag (null counts as active). */
	private Boolean itemActive;
	/** The item's origin (HEAD_OFFICE on a store whose catalogue is the head office's; null otherwise). */
	private RecordOrigin origin;
	private String familyName;
	private String subFamilyName;
}
