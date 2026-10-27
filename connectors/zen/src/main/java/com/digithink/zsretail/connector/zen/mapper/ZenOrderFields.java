package com.digithink.zsretail.connector.zen.mapper;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 3.1.1 (store orders): the field names of ZEN's two pages of the store orders and their mapping, in one place (a field
 * ZEN renames is one line here), as the contract sent to ZEN gives them:
 * <ul>
 * <li>GET variantStock?itemNo=&amp;locationCode= → [{itemNo, variantCode, quantity}] (a plain array, or OData's
 * {"value": [...]});</li>
 * <li>POST salesOrders {locationCode, customerNo, externalDocumentNo, orderDate, lines: [{itemNo, variantCode,
 * quantity}]} → {orderNo};</li>
 * <li>the lookup before it: GET salesOrders?$filter=externalDocumentNo eq '..'&amp;$select=orderNo → {"value":
 * [{orderNo}]}.</li>
 * </ul>
 */
public final class ZenOrderFields {

	// The variant stock page: query parameters and row fields
	public static final String STOCK_ITEM_PARAM = "itemNo";
	public static final String STOCK_LOCATION_PARAM = "locationCode";
	public static final String STOCK_ITEM_NO = "itemNo";
	public static final String STOCK_VARIANT_CODE = "variantCode";
	public static final String STOCK_QUANTITY = "quantity";

	// The sales order: header, lines, answer
	public static final String ORDER_LOCATION = "locationCode";
	public static final String ORDER_CUSTOMER = "customerNo";
	public static final String ORDER_EXTERNAL_NO = "externalDocumentNo";
	public static final String ORDER_DATE = "orderDate";
	public static final String ORDER_LINES = "lines";
	public static final String LINE_ITEM_NO = "itemNo";
	public static final String LINE_VARIANT_CODE = "variantCode";
	public static final String LINE_QUANTITY = "quantity";
	public static final String ORDER_NO = "orderNo";

	/** OData's collection: the rows under "value". */
	static final String VALUE = "value";

	private ZenOrderFields() {
	}

	/** The rows of the variant stock answer, as an array or under "value"; a row without an item number is left out. */
	public static List<ErpVariantStockDTO> stockRows(JsonNode answer) {
		List<ErpVariantStockDTO> rows = new ArrayList<>();
		JsonNode array = answer == null ? null : answer.isArray() ? answer : answer.get(VALUE);
		if (array == null || !array.isArray()) {
			return rows;
		}
		for (JsonNode row : array) {
			String itemNo = text(row, STOCK_ITEM_NO);
			if (itemNo.isEmpty()) {
				continue;
			}
			JsonNode quantity = row.get(STOCK_QUANTITY);
			rows.add(new ErpVariantStockDTO(itemNo, text(row, STOCK_VARIANT_CODE),
					quantity == null || quantity.isNull() ? BigDecimal.ZERO : quantity.decimalValue()));
		}
		return rows;
	}

	/** The body of the POST: the order's header, its date as yyyy-MM-dd, its lines (an empty variant code as ""). */
	public static ObjectNode orderBody(ErpSalesOrderDTO order) {
		ObjectNode body = JsonNodeFactory.instance.objectNode();
		body.put(ORDER_LOCATION, order.getLocationCode());
		body.put(ORDER_CUSTOMER, order.getCustomerNo());
		body.put(ORDER_EXTERNAL_NO, order.getExternalDocumentNo());
		body.put(ORDER_DATE, order.getOrderDate() == null ? null
				: order.getOrderDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
		ArrayNode lines = body.putArray(ORDER_LINES);
		for (ErpSalesOrderDTO.Line line : order.getLines()) {
			ObjectNode row = lines.addObject();
			row.put(LINE_ITEM_NO, line.getItemNo());
			row.put(LINE_VARIANT_CODE, line.getVariantCode() == null ? "" : line.getVariantCode());
			row.put(LINE_QUANTITY, line.getQuantity());
		}
		return body;
	}

	/** The ERP's order number of the POST's answer; empty when it has none. */
	public static String orderNoOf(JsonNode answer) {
		return answer == null ? "" : text(answer, ORDER_NO);
	}

	/** The order number of the lookup's first row (under "value", or a single object); empty when none was found. */
	public static String foundOrderNo(JsonNode answer) {
		if (answer == null) {
			return "";
		}
		JsonNode array = answer.isArray() ? answer : answer.get(VALUE);
		if (array != null && array.isArray()) {
			return array.size() == 0 ? "" : text(array.get(0), ORDER_NO);
		}
		return text(answer, ORDER_NO);
	}

	/** externalDocumentNo eq 'S01-2026-000012' (a quote doubled). */
	public static String lookupFilter(String externalDocumentNo) {
		String value = externalDocumentNo == null ? "" : externalDocumentNo.trim().replace("'", "''");
		return ORDER_EXTERNAL_NO + " eq '" + value + "'";
	}

	private static String text(JsonNode row, String field) {
		JsonNode value = row == null ? null : row.get(field);
		return value == null || value.isNull() ? "" : value.asText().trim();
	}
}
