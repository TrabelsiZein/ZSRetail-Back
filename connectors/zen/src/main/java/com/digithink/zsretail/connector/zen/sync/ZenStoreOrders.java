package com.digithink.zsretail.connector.zen.sync;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.connector.zen.client.ZenReadException;
import com.digithink.zsretail.connector.zen.client.ZenRestClient;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.mapper.ZenOrderFields;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderResult;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.log4j.Log4j2;

/**
 * 3.1.1 (store orders): the stores' orders in ZEN's NAV: the variants' stock on ZEN's page infoArticles, the sales
 * orders on its page commandesVente; the fields in {@link ZenOrderFields}. ZenConnector answers fetchVariantStock and
 * postSalesOrder from here.
 * <p>
 * Posting first looks up NAV's order of the same referenceZS (the store's order number) with its lines: an order
 * posted again after a lost answer is never doubled. The lookup is required: when the page cannot answer it, the order
 * is not posted (failure, retried by the head office's job); except when NAV answers that referenceZS is not
 * filterable: the lookup is skipped, the order posted, and the communications log message says so (the duplicate check
 * waits for ZEN to make the field filterable). Then:
 * <ul>
 * <li>found: reused; the lines NAV does not have (a posting that stopped half-way) are posted one by one;</li>
 * <li>none: one POST of the order with its lines nested (deep insert). When NAV answers a 4xx refusing the nested lines,
 * the header is posted alone, then one POST per line on commandesVente(id)/lignesCommande.</li>
 * </ul>
 * The answer is NAV's order read with its lines: its numero (the external reference), its message, and per line the
 * quantity it accepted (quantite) and its message. The communications log message names the path taken.
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
@Log4j2
public class ZenStoreOrders {

	/** The communications log's note when NAV cannot filter on the order number. */
	static final String LOOKUP_SKIPPED = "duplicate check skipped: " + ZenOrderFields.ORDER_REFERENCE
			+ " not filterable";

	private final ZenRestClient client;
	private final ZenProperties properties;

	public ZenStoreOrders(ZenRestClient client, ZenProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	/**
	 * NAV's stock of every variant of the item for the ERP customer (the store's); ZenReadException naming the page when
	 * it fails.
	 */
	public List<ErpVariantStockDTO> variantStock(String itemNo, String customerNo) {
		return client.readVariantStock(itemNo, customerNo);
	}

	/**
	 * The order in NAV: success with NAV's number and its answer (the order found by its external document number, or
	 * the one posted), the body sent and NAV's order for the communications log; failure with the reason, nothing posted
	 * after a failed lookup.
	 */
	public ErpSalesOrderResult post(ErpSalesOrderDTO order) {
		String number = order.getExternalDocumentNo() == null ? "" : order.getExternalDocumentNo().trim();
		String mode = properties.getOrder().getMode();
		ObjectNode body = ZenOrderFields.orderBody(order, mode);
		if (number.isEmpty()) {
			return ErpSalesOrderResult.of(ErpOperationResult
					.failure("zen: a sales order without its external document number is not posted", body));
		}
		String url = null;
		String skipped = "";
		try {
			url = client.salesOrdersUrl();
			JsonNode found = null;
			try {
				found = client.findSalesOrder(number);
			} catch (ZenReadException e) {
				if (!lookupNotFilterable(e)) {
					throw e;
				}
				log.warn("zen: NAV cannot filter on {} ({}); the order {} is posted without its duplicate check",
						ZenOrderFields.ORDER_REFERENCE, e.getMessage(), number);
				skipped = "; " + LOOKUP_SKIPPED;
			}
			if (found != null) {
				return reused(order, number, found, body, url, mode);
			}
			JsonNode answer;
			String path;
			try {
				answer = client.postSalesOrder(body);
				path = "posted with its " + order.getLines().size() + " lines in one call (deep insert)";
			} catch (ZenReadException e) {
				if (!nestedLinesRefused(e)) {
					throw e;
				}
				log.warn("zen: NAV refused the lines nested in the order {} ({}); header then one POST per line", number,
						e.getMessage());
				answer = postHeaderThenLines(order, mode);
				path = "NAV refused the nested lines: header then " + order.getLines().size() + " lines posted one by one";
			}
			return answered(number, ZenOrderFields.orderOf(answer), body, url, path + skipped);
		} catch (ZenReadException e) {
			ErpSalesOrderResult failure = ErpSalesOrderResult
					.of(ErpOperationResult.failure(e.getMessage() + skipped, body));
			failure.setUrl(url);
			return failure;
		}
	}

	/** NAV's order already there: reused, its missing lines posted, then read again with its lines. */
	private ErpSalesOrderResult reused(ErpSalesOrderDTO order, String number, JsonNode found, ObjectNode body, String url,
			String mode) {
		String orderNo = ZenOrderFields.orderNoOf(found);
		List<ErpSalesOrderDTO.Line> missing = ZenOrderFields.missingLines(order, found);
		JsonNode navOrder = found;
		if (!missing.isEmpty()) {
			String id = idOf(found, number);
			for (ErpSalesOrderDTO.Line line : missing) {
				client.postSalesOrderLine(id, ZenOrderFields.lineBody(line, mode));
			}
			navOrder = client.readSalesOrder(id);
		}
		String path = "NAV's order " + orderNo + " of " + number + " already exists: reused, "
				+ (missing.isEmpty() ? "nothing posted" : "its " + missing.size() + " missing lines posted");
		return answered(number, navOrder, body, url, path);
	}

	/** The header alone, then each line on its order's lines: NAV's order as the header's POST answered it. */
	private JsonNode postHeaderThenLines(ErpSalesOrderDTO order, String mode) {
		JsonNode header = ZenOrderFields.orderOf(client.postSalesOrder(ZenOrderFields.headerBody(order)));
		String id = idOf(header, order.getExternalDocumentNo());
		for (ErpSalesOrderDTO.Line line : order.getLines()) {
			client.postSalesOrderLine(id, ZenOrderFields.lineBody(line, mode));
		}
		return header;
	}

	/**
	 * Success with NAV's numero and the answer of the order with its lines (read again by its id when the answer has no
	 * lines; when that read fails, success without the lines' answer: the order is in NAV); failure without numero.
	 */
	private ErpSalesOrderResult answered(String number, JsonNode navOrder, ObjectNode body, String url, String path) {
		String orderNo = ZenOrderFields.orderNoOf(navOrder);
		if (orderNo.isEmpty()) {
			ErpSalesOrderResult failure = ErpSalesOrderResult.of(ErpOperationResult.failure(
					"zen: NAV answered the order " + number + " without its " + ZenOrderFields.ORDER_NO, body));
			failure.setActualResponsePayload(navOrder);
			failure.setUrl(url);
			return failure;
		}
		JsonNode withLines = navOrder;
		String message = "NAV's order " + orderNo + " of " + number + ": " + path;
		if (!ZenOrderFields.hasLines(navOrder)) {
			try {
				withLines = client.readSalesOrder(idOf(navOrder, number));
			} catch (ZenReadException e) {
				log.warn("zen: the order {} is in NAV as {}, its lines could not be read: {}", number, orderNo,
						e.getMessage());
				message += "; its lines could not be read (" + e.getMessage() + ")";
			}
		}
		ErpSalesOrderResult result = ErpSalesOrderResult
				.of(ErpOperationResult.success(orderNo, body, withLines == null ? navOrder : withLines, url));
		result.setMessage(message);
		ZenOrderFields.answerInto(withLines == null ? navOrder : withLines, result);
		log.info("zen: {}", message);
		return result;
	}

	private static String idOf(JsonNode navOrder, String number) {
		String id = ZenOrderFields.orderIdOf(navOrder);
		if (id.isEmpty()) {
			throw new ZenReadException(ZenOrderFields.SALES_ORDERS_PAGE,
					"zen: NAV answered the order " + number + " without its " + ZenOrderFields.ORDER_ID, null);
		}
		return id;
	}

	/**
	 * NAV refused the lookup's filter because the order number's field cannot be filtered on ("Field 'referenceZS' is not
	 * filterable"), read in NAV's answer (the exception's message names the URL, hence the field, always). Any other
	 * failure of the lookup stops the posting.
	 */
	static boolean lookupNotFilterable(ZenReadException e) {
		if (!(e.getCause() instanceof HttpStatusCodeException)) {
			return false;
		}
		String answer = ((HttpStatusCodeException) e.getCause()).getResponseBodyAsString().toLowerCase(Locale.ROOT);
		return answer.contains("'" + ZenOrderFields.ORDER_REFERENCE.toLowerCase(Locale.ROOT) + "' is not filterable");
	}

	/**
	 * NAV refused the lines nested in the order: a 4xx whose answer names the lines' collection (lignesCommande), a
	 * navigation property or a deep insert. Any other refusal is the order's failure.
	 */
	static boolean nestedLinesRefused(ZenReadException e) {
		if (!(e.getCause() instanceof HttpStatusCodeException)) {
			return false;
		}
		HttpStatusCodeException http = (HttpStatusCodeException) e.getCause();
		if (http.getRawStatusCode() < 400 || http.getRawStatusCode() >= 500) {
			return false;
		}
		String answer = http.getResponseBodyAsString().toLowerCase(Locale.ROOT);
		return answer.contains(ZenOrderFields.ORDER_LINES.toLowerCase(Locale.ROOT))
				|| answer.contains("navigation property") || answer.contains("deep insert");
	}
}
