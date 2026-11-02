package com.digithink.zsretail.erp.api.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 3.1.1 (ParaFendri, step 7): what the ERP answered to an invoice asked for a ticket already in it
 * (ErpConnector.requestInvoice): on success the ERP's invoice number (also the external reference) and the invoice as a
 * PDF when the ERP gave one. The communications log keeps the answer without the PDF (actualResponsePayload).
 */
@Getter
@Setter
public class ErpInvoiceResult extends ErpOperationResult {

	/** The ERP's invoice number; null on a failure. */
	private String invoiceNo;

	/** The invoice as a PDF; null when the ERP gave none. */
	private byte[] pdf;

	/** The same outcome, as an invoice's result without the invoice (yet). */
	public static ErpInvoiceResult of(ErpOperationResult result) {
		ErpInvoiceResult typed = new ErpInvoiceResult();
		typed.setSuccess(result.isSuccess());
		typed.setExternalReference(result.getExternalReference());
		typed.setUrl(result.getUrl());
		typed.setMessage(result.getMessage());
		typed.setActualRequestPayload(result.getActualRequestPayload());
		typed.setActualResponsePayload(result.getActualResponsePayload());
		result.getWarnings().forEach(typed::addWarning);
		return typed;
	}

	/** A success: the invoice number (the external reference too) and its PDF, null when the ERP gave none. */
	public static ErpInvoiceResult invoiced(String invoiceNo, byte[] pdf, Object actualRequestPayload,
			Object actualResponsePayload, String url) {
		ErpInvoiceResult result = of(
				ErpOperationResult.success(invoiceNo, actualRequestPayload, actualResponsePayload, url));
		result.setInvoiceNo(invoiceNo);
		result.setPdf(pdf);
		return result;
	}
}
