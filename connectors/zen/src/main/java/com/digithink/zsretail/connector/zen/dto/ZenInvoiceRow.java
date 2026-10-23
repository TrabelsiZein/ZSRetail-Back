package com.digithink.zsretail.connector.zen.dto;

import java.io.IOException;
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
 * Item variants (ZEN), step 9: a row of NAV's posted sales invoices page (SalesInvoice), kept field by field as
 * Happyness's NavPosInvoiceRow: the lines property is a setting, so no field name of it is fixed here.
 * {@link #resolveLines} turns the expanded lines into {@link ZenInvoiceLineRow}. Read by {@link Reader}: the amounts stay
 * exact decimals (a tree of the default mapper holds doubles, 90.000 would become 90.0). The header has no totals and no
 * Prices_Including_VAT (ZEN's prices are before VAT).
 */
@JsonDeserialize(using = ZenInvoiceRow.Reader.class)
public class ZenInvoiceRow {

	public static final String NO = "No";
	public static final String CUSTOMER_NO = "Sell_to_Customer_No";
	public static final String CUSTOMER_NAME = "Sell_to_Customer_Name";
	public static final String DOCUMENT_DATE = "Document_Date";
	public static final String POSTING_DATE = "Posting_Date";
	/** True once NAV cancelled the invoice (a credit memo cancels it): never read. */
	public static final String CANCELLED = "Cancelled";
	/** True for an invoice that cancels a credit memo: read, with a warning. */
	public static final String CORRECTIVE = "Corrective";

	/** The header fields read ($select). */
	public static final String FIELDS = String.join(",", NO, CUSTOMER_NO, CUSTOMER_NAME, DOCUMENT_DATE, POSTING_DATE,
			CANCELLED, CORRECTIVE);

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

	/** The invoice number, trimmed; "" when none. */
	@JsonIgnore
	public String getNumber() {
		String number = text(NO);
		return number == null ? "" : number.trim();
	}

	/** Cancelled true (a JSON boolean or the text true). */
	@JsonIgnore
	public boolean isCancelled() {
		return "true".equalsIgnoreCase(String.valueOf(text(CANCELLED)).trim());
	}

	/** Corrective true (a JSON boolean or the text true). */
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
