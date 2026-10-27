package com.digithink.zsretail.erp.api.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 3.1.1 (store orders): what the ERP answered to a store's order (ErpConnector.postSalesOrder): on success the ERP's
 * order number (the external reference), its message on the order and, per line, the quantity it accepted and its
 * message. The head office keeps them on the order and sends them down to the store; the whole answer stays in the
 * communications log (actualResponsePayload).
 */
@Getter
@Setter
public class ErpSalesOrderResult extends ErpOperationResult {

	/** The ERP's message on the order; null when it gave none. */
	private String orderMessage;

	/** The ERP's lines, as it answered them (empty when it gave none). */
	private List<Line> lines = new ArrayList<>();

	/** The same outcome, as a sales order's result without the ERP's answer (yet). */
	public static ErpSalesOrderResult of(ErpOperationResult result) {
		ErpSalesOrderResult typed = new ErpSalesOrderResult();
		typed.setSuccess(result.isSuccess());
		typed.setExternalReference(result.getExternalReference());
		typed.setUrl(result.getUrl());
		typed.setMessage(result.getMessage());
		typed.setActualRequestPayload(result.getActualRequestPayload());
		typed.setActualResponsePayload(result.getActualResponsePayload());
		return typed;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Line {
		/** The ERP item number. */
		private String itemNo;
		/** The ERP variant code; empty for an item without variants. */
		private String variantCode;
		/** The quantity the ERP accepted; null when it gave none. */
		private BigDecimal acceptedQty;
		/** The ERP's message on the line; null when it gave none. */
		private String message;
	}
}
