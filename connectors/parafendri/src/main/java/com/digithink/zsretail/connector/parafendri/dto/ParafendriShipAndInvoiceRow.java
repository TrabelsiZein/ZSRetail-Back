package com.digithink.zsretail.connector.parafendri.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Step 7: a row of the page shipAndInvoice as Business Central answers it (the POST's answer, or a row of the lookup by
 * orderNo, its key): the sales order shipped and invoiced, its invoice number and the invoice as a PDF in base64. The
 * page has no field for the customer's fiscal registration or name (open with the BC contact).
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParafendriShipAndInvoiceRow {

	private String orderNo;
	private String invoiceNo;
	private String invoicePdfBase64;
	private String lastModifiedDateTime;
}
