package com.digithink.zsretail.erp.navpospages.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A row of the barcodes page (ItemBarCodePOS); Entry_No grows with each new row. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NavPosBarcodeRow {

	@JsonProperty("Item_No")
	private String itemNo;

	@JsonProperty("Cross_Reference_No")
	private String crossReferenceNo;

	@JsonProperty("Entry_No")
	private Long entryNo;

	public NavPosBarcodeRow(String itemNo, String crossReferenceNo, Long entryNo) {
		this.itemNo = itemNo;
		this.crossReferenceNo = crossReferenceNo;
		this.entryNo = entryNo;
	}
}
