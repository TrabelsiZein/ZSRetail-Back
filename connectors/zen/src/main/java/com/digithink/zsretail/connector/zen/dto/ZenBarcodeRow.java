package com.digithink.zsretail.connector.zen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A row of ZEN's page barCodes (step 12a, the names of the API). The engine's barcode cursor is lastModifiedDateTime
 * (its position: {@link com.digithink.zsretail.connector.zen.mapper.ZenMapper#position}); the id tells two reads of
 * the same row apart. Not read: unitOfMeasureCode (every row kept whatever its unit, a question for ZEN) and
 * description.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZenBarcodeRow {

	public static final String FIELDS = "id,itemNo,variantCode,barCode,lastModifiedDateTime";

	/** The row's GUID in the API. */
	private String id;
	private String itemNo;
	private String variantCode;
	private String barCode;
	/** ISO with its offset; 0001-01-01T00:00:00Z when NAV has none. */
	private String lastModifiedDateTime;
}
