package com.digithink.zsretail.connector.zen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A row of the barcodes page: Happyness's barcode page and the variant code. Entry_No grows with each new row (the
 * engine's barcode cursor).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZenBarcodeRow {

	public static final String FIELDS = "Item_No,Variant_Code,Cross_Reference_No,Entry_No";

	@JsonProperty("Item_No")
	private String itemNo;

	@JsonProperty("Variant_Code")
	private String variantCode;

	@JsonProperty("Cross_Reference_No")
	private String crossReferenceNo;

	@JsonProperty("Entry_No")
	private Long entryNo;
}
