package com.digithink.zsretail.connector.zen.dto;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Item variants (ZEN), step 9: a row of the posted sales invoices page of ZEN's franchise API (salesInvoices), kept
 * field by field as Happyness's NavPosInvoiceRow: the lines property is a setting, so no field name of it is fixed here.
 * {@link #resolveLines} turns the expanded lines into {@link ZenInvoiceLineRow}. Read by {@link Reader}: the amounts stay
 * exact decimals (a tree of the default mapper holds doubles, 90.000 would become 90.0). The header gives the totals
 * before and with VAT (amount, amountIncludingVAT); no pricesIncludingVAT (ZEN's prices are before VAT). The header is
 * read without $select: cancelled and corrective are read once the page gives them (not yet, a question for ZEN).
 */
@JsonDeserialize(using = ZenInvoiceRow.Reader.class)
public class ZenInvoiceRow {

	public static final String NO = "number";
	public static final String CUSTOMER_NO = "sellToCustomerNo";
	public static final String CUSTOMER_NAME = "sellToCustomerName";
	public static final String DOCUMENT_DATE = "documentDate";
	public static final String POSTING_DATE = "postingDate";
	/** The total before VAT. */
	public static final String AMOUNT = "amount";
	/** The total with VAT. */
	public static final String AMOUNT_INCL_VAT = "amountIncludingVAT";
	/** Blank for the local currency. */
	public static final String CURRENCY = "currencyCode";
	/** True once NAV cancelled the invoice (a credit memo cancels it): never read. Not on the page yet. */
	public static final String CANCELLED = "cancelled";
	/** True for an invoice that cancels a credit memo: read, with a warning. Not on the page yet. */
	public static final String CORRECTIVE = "corrective";

	/** Exact decimals: 41.000 stays 41.000 (no double, no trailing zeros stripped). */
	private static final ObjectMapper DECIMALS = new ObjectMapper()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

	/** Reads a row field by field with {@link #DECIMALS}. */
	public static class Reader extends JsonDeserializer<ZenInvoiceRow> {
		@Override
		public ZenInvoiceRow deserialize(JsonParser parser, DeserializationContext context) throws IOException {
			JsonNode tree = DECIMALS.readTree(parser);
			ZenInvoiceRow row = new ZenInvoiceRow();
			if (tree != null) {
				tree.fields().forEachRemaining(field -> row.set(field.getKey(), field.getValue()));
			}
			return row;
		}
	}

	private final Map<String, JsonNode> fields = new LinkedHashMap<>();

	@JsonIgnore
	private List<ZenInvoiceLineRow> lines = new ArrayList<>();

	@JsonAnySetter
	public void set(String name, JsonNode value) {
		fields.put(name, value);
	}

	public Map<String, JsonNode> getFields() {
		return Collections.unmodifiableMap(fields);
	}

	/** The text of a field, null when absent or JSON null. */
	public String text(String name) {
		JsonNode value = fields.get(name);
		return value == null || value.isNull() ? null : value.asText();
	}

	/** The decimal of a field, exact; null when absent, JSON null or not a number. */
	public BigDecimal decimal(String name) {
		JsonNode value = fields.get(name);
		if (value == null || value.isNull()) {
			return null;
		}
		if (value.isNumber()) {
			return value.decimalValue();
		}
		try {
			return new BigDecimal(value.asText().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The invoice number, trimmed; "" when none. */
	@JsonIgnore
	public String getNumber() {
		String number = text(NO);
		return number == null ? "" : number.trim();
	}

	/** cancelled true (a JSON boolean or the text true); false while the page does not give it. */
	@JsonIgnore
	public boolean isCancelled() {
		return "true".equalsIgnoreCase(String.valueOf(text(CANCELLED)).trim());
	}

	/** corrective true (a JSON boolean or the text true); false while the page does not give it. */
	@JsonIgnore
	public boolean isCorrective() {
		return "true".equalsIgnoreCase(String.valueOf(text(CORRECTIVE)).trim());
	}

	/** The lines of the expanded property linesExpand (none when absent); returns this row. */
	public ZenInvoiceRow resolveLines(String linesExpand) {
		JsonNode value = fields.get(linesExpand);
		lines = value == null || !value.isArray() ? new ArrayList<>()
				: DECIMALS.convertValue(value, new TypeReference<List<ZenInvoiceLineRow>>() {
				});
		return this;
	}

	@JsonIgnore
	public List<ZenInvoiceLineRow> getLines() {
		return lines;
	}
}
