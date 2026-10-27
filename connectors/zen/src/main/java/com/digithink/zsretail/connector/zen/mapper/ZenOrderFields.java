package com.digithink.zsretail.connector.zen.mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 3.1.1 (store orders): the field names of ZEN's two pages of the store orders and their mapping, in one place (a field
 * ZEN renames is one line here):
 * <ul>
 * <li>ZEN's real page GET infoArticles?$filter=article eq '..' and client eq '..' → {"value": [{article, codeVariante,
 * client, ..., ordreTaille, ..., stock, qteVrac, disponible, calculeLe}]}, one row per variant, the stock of the item
 * for the store's ERP customer (no location): the quantity is disponible, never stock;</li>
 * <li>as the contract sent to ZEN gives them (commandesVente not published yet): POST salesOrders {locationCode,
 * customerNo, externalDocumentNo, orderDate, lines: [{itemNo, variantCode, quantity}]} → {orderNo};</li>
 * <li>the lookup before it: GET salesOrders?$filter=externalDocumentNo eq '..'&amp;$select=orderNo → {"value":
 * [{orderNo}]}.</li>
 * </ul>
 */
public final class ZenOrderFields {

	// The variant stock page (infoArticles): the filter's fields and the row's
	public static final String STOCK_ITEM_NO = "article";
	public static final String STOCK_CUSTOMER = "client";
	public static final String STOCK_VARIANT_CODE = "codeVariante";
	/** What the store can order: disponible, never stock (stock is one of the details). */
	public static final String STOCK_QUANTITY = "disponible";
	public static final String STOCK_SIZE_ORDER = "ordreTaille";
	public static final String STOCK_COMPUTED_AT = "calculeLe";
	/** The details of a row (the tooltip of the store's cell), in this order: the field, its label. */
	static final String[][] STOCK_DETAILS = { { "prixUnitaire", "Prix unitaire" }, { "remisePourcent", "Remise %" },
			{ "prixNet", "Prix net" }, { "stock", "Stock" }, { "qteVrac", "Qt\u00e9 vrac" },
			{ "vracAutorise", "Vrac autoris\u00e9" }, { "contenanceSerie", "Contenance s\u00e9rie" },
			{ "colisPossibles", "Colis possibles" }, { STOCK_COMPUTED_AT, "Calcul\u00e9 le" } };
	static final DateTimeFormatter COMPUTED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

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

	/**
	 * The rows of the variant stock answer, as an array or under "value"; a row without an item number is left out. The
	 * quantity is disponible (null when the row has none), the size order ordreTaille, the details the labelled fields
	 * the row has (an empty value left out; a boolean Oui / Non, calculeLe as dd/MM/yyyy HH:mm in the given zone).
	 */
	public static List<ErpVariantStockDTO> stockRows(JsonNode answer, ZoneId zone) {
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
			JsonNode sizeOrder = row.get(STOCK_SIZE_ORDER);
			LinkedHashMap<String, String> details = new LinkedHashMap<>();
			for (String[] detail : STOCK_DETAILS) {
				String value = detailText(row.get(detail[0]), detail[0], zone);
				if (!value.isEmpty()) {
					details.put(detail[1], value);
				}
			}
			rows.add(new ErpVariantStockDTO(itemNo, text(row, STOCK_VARIANT_CODE),
					quantity == null || !quantity.isNumber() ? null : quantity.decimalValue(),
					details.isEmpty() ? null : details,
					sizeOrder == null || !sizeOrder.isNumber() ? null : sizeOrder.intValue()));
		}
		return rows;
	}

	/** article eq '43ZBPND01' and client eq '4110001' (a quote doubled). */
	public static String stockFilter(String itemNo, String customerNo) {
		return STOCK_ITEM_NO + " eq '" + quoted(itemNo) + "' and " + STOCK_CUSTOMER + " eq '" + quoted(customerNo) + "'";
	}

	private static String detailText(JsonNode value, String field, ZoneId zone) {
		if (value == null || value.isNull()) {
			return "";
		}
		if (value.isBoolean()) {
			return value.booleanValue() ? "Oui" : "Non";
		}
		String text = value.asText().trim();
		if (STOCK_COMPUTED_AT.equals(field) && !text.isEmpty()) {
			try {
				return OffsetDateTime.parse(text).atZoneSameInstant(zone).format(COMPUTED_AT);
			} catch (DateTimeParseException e) {
				return text;
			}
		}
		return text;
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
		return ORDER_EXTERNAL_NO + " eq '" + quoted(externalDocumentNo) + "'";
	}

	private static String quoted(String value) {
		return value == null ? "" : value.trim().replace("'", "''");
	}

	private static String text(JsonNode row, String field) {
		JsonNode value = row == null ? null : row.get(field);
		return value == null || value.isNull() ? "" : value.asText().trim();
	}
}
