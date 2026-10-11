package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The head office's answer for one document of a batch (task 2.3), in the order of the batch. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SalesCopyResultDTO {

	/** Sales number, return number or session number, as sent; null for an empty document. */
	private String documentNumber;

	private boolean accepted;

	/** Why the document was rejected; null when accepted. */
	private String message;

	public static SalesCopyResultDTO accepted(String documentNumber) {
		return new SalesCopyResultDTO(documentNumber, true, null);
	}

	public static SalesCopyResultDTO rejected(String documentNumber, String message) {
		return new SalesCopyResultDTO(documentNumber, false, message);
	}
}
