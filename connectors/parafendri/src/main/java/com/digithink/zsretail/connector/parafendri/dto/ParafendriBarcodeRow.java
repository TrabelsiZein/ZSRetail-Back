package com.digithink.zsretail.connector.parafendri.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Step 4: a row of the page barCodes of the company, company-wide (no location): one barcode of one item. id (a GUID)
 * is the row's identity, so a barcode changed in Business Central replaces the old one. Read, not used:
 * unitOfMeasureCode (item_barcode has no unit), description (empty).
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParafendriBarcodeRow {

	private String id;
	private String itemNo;
	private String barCode;
	private String unitOfMeasureCode;
	private String description;
	private String lastModifiedDateTime;
}
