package com.digithink.zsretail.connector.zen.mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderResult;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 3.1.1 (store orders): ZEN's two pages of the store orders under the zsretail API, their field names and their mapping,
 * in one place (a field ZEN renames is one line here):
 * <ul>
 * <li>GET infoArticles?$filter=article eq '..' and client eq '..' → {"value": [{article, codeVariante, client, ...,
 * ordreTaille, ..., stock, qteVrac, disponible, calculeLe}]}, one row per variant, the stock of the item for the store's
 * ERP customer (no location): the quantity is disponible, never stock;</li>
 * <li>commandesVente, the sales orders: POST {referenceZS, commandeZS, client, dateCommande, lignesCommande:
 * [{article, codeVariante, mode, quantiteDemandee}]} → {id, numero, statut, message, montantTTC, ...}; the lines
 * (contained collection lignesCommande) answer quantite (accepted), colisAcceptes, message, prixUnitaire,
 * remisePourcent, montantTTC. The lookup before it: GET commandesVente?$filter=referenceZS eq
 * '..'&amp;$expand=lignesCommande. A line alone: POST commandesVente(id)/lignesCommande. numeroDocExterne is never
 * sent (read-only on ZEN's side, 2026-10-28).</li>
 * </ul>
 */
public final class ZenOrderFields {

	// The pages (under companies(id) of the zsretail API)
	public static final String VARIANT_STOCK_PAGE = "infoArticles";
	public static final String SALES_ORDERS_PAGE = "commandesVente";
	/** The contained collection of an order's lines (deep insert, $expand, a line alone). */
	public static final String ORDER_LINES = "lignesCommande";

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
			{ "prixNet", "Prix net" }, { "stock", "Stock" }, { "qteVrac", "Qté vrac" },
			{ "vracAutorise", "Vrac autorisé" }, { "contenanceSerie", "Contenance série" },
			{ "colisPossibles", "Colis possibles" }, { "qteSerieType", "Types de série" },
			{ STOCK_COMPUTED_AT, "Calculé le" } };
	static final DateTimeFormatter COMPUTED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

	// The sales order (commandesVente): header sent
	/** The store's order number: the lookup's key (ZEN's field for it; numeroDocExterne is read-only there). */
	public static final String ORDER_REFERENCE = "referenceZS";
	/** Always true: an order of ZS. */
	public static final String ORDER_FROM_ZS = "commandeZS";
	/** The store's ERP customer number. */
	public static final String ORDER_CUSTOMER = "client";
	public static final String ORDER_DATE = "dateCommande";
	// The header answered
	public static final String ORDER_ID = "id";
	public static final String ORDER_NO = "numero";
	public static final String ORDER_STATUS = "statut";
	public static final String ORDER_MESSAGE = "message";
	// A line sent
	public static final String LINE_ITEM_NO = "article";
	public static final String LINE_VARIANT_CODE = "codeVariante";
	/** erp.zen.order.mode (vrac by default; NAV requires colis or vrac); left out when set empty. */
	public static final String LINE_MODE = "mode";
	/** The pieces the store asks for. */
	public static final String LINE_REQUESTED = "quantiteDemandee";
	/** 3.1.1: the mode of a colis line (a pieces line sends erp.zen.order.mode, vrac by default). */
	public static final String MODE_COLIS = "colis";
	/** 3.1.1: the colis a colis line asks for. */
	public static final String LINE_COLIS = "nbColis";
	/** 3.1.1: the colis NAV accepted on a colis line. */
	public static final String LINE_COLIS_ACCEPTED = "colisAcceptes";
	// A line answered
	/** The pieces NAV accepted. */
	public static final String LINE_ACCEPTED = "quantite";
	public static final String LINE_MESSAGE = "message";

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

	// ─── The sales order ─────────────────────────────────────────

	/** The deep insert: the header with its lines nested under lignesCommande. */
	public static ObjectNode orderBody(ErpSalesOrderDTO order, String mode) {
		ObjectNode body = headerBody(order);
		ArrayNode lines = body.putArray(ORDER_LINES);
		for (ErpSalesOrderDTO.Line line : order.getLines()) {
			lines.add(lineBody(line, mode));
		}
		return body;
	}

	/**
	 * The header: referenceZS (the store's order number), commandeZS always true, client (the store's ERP customer
	 * number, the only field naming the store), dateCommande as yyyy-MM-dd; no numeroDocExterne, no
	 * dateLivraisonSouhaitee.
	 */
	public static ObjectNode headerBody(ErpSalesOrderDTO order) {
		ObjectNode body = JsonNodeFactory.instance.objectNode();
		body.put(ORDER_REFERENCE, order.getExternalDocumentNo());
		body.put(ORDER_FROM_ZS, true);
		body.put(ORDER_CUSTOMER, order.getCustomerNo());
		body.put(ORDER_DATE, order.getOrderDate() == null ? null
				: order.getOrderDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
		return body;
	}

	/**
	 * A line of pieces: article, codeVariante ("" without one), mode only when set, quantiteDemandee; no nbColis. 3.1.1, a
	 * line of colis: article, codeVariante (NAV requires both on every line), mode "colis", nbColis; no quantiteDemandee.
	 */
	public static ObjectNode lineBody(ErpSalesOrderDTO.Line line, String mode) {
		ObjectNode row = JsonNodeFactory.instance.objectNode();
		row.put(LINE_ITEM_NO, line.getItemNo());
		row.put(LINE_VARIANT_CODE, line.getVariantCode() == null ? "" : line.getVariantCode());
		if (line.isColis()) {
			row.put(LINE_MODE, MODE_COLIS);
			row.put(LINE_COLIS, line.getNbColis());
			return row;
		}
		if (mode != null && !mode.trim().isEmpty()) {
			row.put(LINE_MODE, mode.trim());
		}
		row.put(LINE_REQUESTED, line.getQuantity());
		return row;
	}

	/** The order of an answer: the lookup's first row (under "value"), or the object itself; null when none. */
	public static JsonNode orderOf(JsonNode answer) {
		if (answer == null || answer.isNull()) {
			return null;
		}
		JsonNode array = answer.isArray() ? answer : answer.get(VALUE);
		if (array != null && array.isArray()) {
			return array.size() == 0 ? null : array.get(0);
		}
		return answer;
	}

	/** NAV's order number (numero); empty when it has none. */
	public static String orderNoOf(JsonNode order) {
		return text(order, ORDER_NO);
	}

	/** NAV's id of the order (the address of its lines); empty when it has none. */
	public static String orderIdOf(JsonNode order) {
		return text(order, ORDER_ID);
	}

	/** The order came with its lines (lignesCommande, an array). */
	public static boolean hasLines(JsonNode order) {
		JsonNode lines = order == null ? null : order.get(ORDER_LINES);
		return lines != null && lines.isArray();
	}

	/**
	 * The order's answer: its message (null when empty) and its lines: article, variant, quantite, message; 3.1.1 a line
	 * whose mode is colis is a colis line, with colisAcceptes.
	 */
	public static void answerInto(JsonNode order, ErpSalesOrderResult result) {
		String message = text(order, ORDER_MESSAGE);
		result.setOrderMessage(message.isEmpty() ? null : message);
		List<ErpSalesOrderResult.Line> lines = new ArrayList<>();
		JsonNode rows = order == null ? null : order.get(ORDER_LINES);
		if (rows != null && rows.isArray()) {
			for (JsonNode row : rows) {
				JsonNode accepted = row.get(LINE_ACCEPTED);
				JsonNode colisAccepted = row.get(LINE_COLIS_ACCEPTED);
				String lineMessage = text(row, LINE_MESSAGE);
				lines.add(new ErpSalesOrderResult.Line(text(row, LINE_ITEM_NO), text(row, LINE_VARIANT_CODE),
						accepted == null || !accepted.isNumber() ? null : accepted.decimalValue(),
						lineMessage.isEmpty() ? null : lineMessage, isColis(row),
						!isColis(row) || colisAccepted == null || !colisAccepted.isNumber() ? null : colisAccepted.intValue()));
			}
		}
		result.setLines(lines);
	}

	/**
	 * The lines of the store's order that NAV's order does not have (by article and variant): after a posting that
	 * stopped half-way (header and some lines), the ones still to post.
	 */
	public static List<ErpSalesOrderDTO.Line> missingLines(ErpSalesOrderDTO order, JsonNode navOrder) {
		Set<String> present = new HashSet<>();
		JsonNode rows = navOrder == null ? null : navOrder.get(ORDER_LINES);
		if (rows != null && rows.isArray()) {
			for (JsonNode row : rows) {
				present.add(lineKey(text(row, LINE_ITEM_NO), (isColis(row) ? COLIS_KEY : "") + text(row, LINE_VARIANT_CODE)));
			}
		}
		List<ErpSalesOrderDTO.Line> missing = new ArrayList<>();
		for (ErpSalesOrderDTO.Line line : order.getLines()) {
			String variant = line.getVariantCode() == null ? "" : line.getVariantCode().trim();
			if (!present.contains(lineKey(line.getItemNo(), (line.isColis() ? COLIS_KEY : "") + variant))) {
				missing.add(line);
			}
		}
		return missing;
	}

	/** 3.1.1: the mark before a colis line's variant in its key (a colis line is never a pieces line of that variant). */
	private static final String COLIS_KEY = "colis|";

	/** 3.1.1: a line of NAV's order in colis (its mode). */
	static boolean isColis(JsonNode row) {
		return MODE_COLIS.equalsIgnoreCase(text(row, LINE_MODE));
	}

	/** article and variant, trimmed, no variant as "". */
	public static String lineKey(String itemNo, String variantCode) {
		return (itemNo == null ? "" : itemNo.trim()) + "\u0000" + (variantCode == null ? "" : variantCode.trim());
	}

	/** referenceZS eq 'ZS01-2026-000012' (a quote doubled). */
	public static String lookupFilter(String externalDocumentNo) {
		return ORDER_REFERENCE + " eq '" + quoted(externalDocumentNo) + "'";
	}

	/** The order of this id, under the sales orders page: commandesVente(id). */
	public static String orderPage(String id) {
		return SALES_ORDERS_PAGE + "(" + id.trim() + ")";
	}

	/** The lines of the order of this id: commandesVente(id)/lignesCommande. */
	public static String orderLinesPage(String id) {
		return orderPage(id) + "/" + ORDER_LINES;
	}

	private static String quoted(String value) {
		return value == null ? "" : value.trim().replace("'", "''");
	}

	private static String text(JsonNode row, String field) {
		JsonNode value = row == null ? null : row.get(field);
		return value == null || value.isNull() ? "" : value.asText().trim();
	}
}
