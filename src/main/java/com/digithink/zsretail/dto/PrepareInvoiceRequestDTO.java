package com.digithink.zsretail.dto;

import lombok.Data;

@Data
public class PrepareInvoiceRequestDTO {
	private String fiscalRegistration;

	/**
	 * Optional customer name to use on the invoice header.
	 * This will be sent to NAV as Bill_to_Name_2 when present.
	 */
	private String invoiceCustomerName;
}
