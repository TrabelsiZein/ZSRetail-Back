package com.digithink.zsretail.connector.zen.sync;

import java.util.List;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.connector.zen.client.ZenReadException;
import com.digithink.zsretail.connector.zen.client.ZenRestClient;
import com.digithink.zsretail.connector.zen.mapper.ZenOrderFields;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 3.1.1 (store orders): the stores' orders in ZEN's NAV: the variants' stock on ZEN's page infoArticles
 * (erp.zen.variantstock.path), the sales orders on the page of the contract sent to ZEN (erp.zen.salesorders.path); the
 * fields in {@link ZenOrderFields}. ZenConnector answers
 * fetchVariantStock and postSalesOrder from here. Posting first looks up NAV's order of the same externalDocumentNo (the
 * store's order number) and reuses its number: an order posted again after a lost answer is never doubled. The lookup
 * is required: when the page cannot answer it, the order is not posted (failure, retried by the head office's job).
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenStoreOrders {

	private final ZenRestClient client;

	public ZenStoreOrders(ZenRestClient client) {
		this.client = client;
	}

	/**
	 * NAV's stock of every variant of the item for the ERP customer (the store's); ZenReadException naming the page when
	 * it fails.
	 */
	public List<ErpVariantStockDTO> variantStock(String itemNo, String customerNo) {
		return client.readVariantStock(itemNo, customerNo);
	}

	/**
	 * The order in NAV: success with NAV's number (the order found by its external document number, or the one the POST
	 * made), the body sent and NAV's answer for the communications log; failure with the reason, nothing posted after a
	 * failed lookup.
	 */
	public ErpOperationResult post(ErpSalesOrderDTO order) {
		String number = order.getExternalDocumentNo() == null ? "" : order.getExternalDocumentNo().trim();
		ObjectNode body = ZenOrderFields.orderBody(order);
		if (number.isEmpty()) {
			return ErpOperationResult.failure("zen: a sales order without its external document number is not posted", body);
		}
		String url = null;
		try {
			url = client.salesOrdersUrl();
			String existing = client.findSalesOrderNo(number);
			if (!existing.isEmpty()) {
				return ErpOperationResult.success(existing, body,
						"NAV's order " + existing + " of " + number + " already exists: reused, nothing posted", url);
			}
			JsonNode answer = client.postSalesOrder(body);
			String orderNo = ZenOrderFields.orderNoOf(answer);
			if (orderNo.isEmpty()) {
				ErpOperationResult failure = ErpOperationResult.failure(
						"zen: NAV answered the order " + number + " without its " + ZenOrderFields.ORDER_NO, body);
				failure.setActualResponsePayload(answer);
				failure.setUrl(url);
				return failure;
			}
			return ErpOperationResult.success(orderNo, body, answer, url);
		} catch (ZenReadException e) {
			ErpOperationResult failure = ErpOperationResult.failure(e.getMessage(), body);
			failure.setUrl(url);
			return failure;
		}
	}
}
