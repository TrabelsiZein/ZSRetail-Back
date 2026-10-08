package com.digithink.zsretail.erp.navpospages.dto;

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
 * Invoices from the ERP, step (a): a row of the invoices page (FactureFranchise), kept field by field: the customer
 * field and the lines property are configuration lines, so no field name is fixed here. {@link #resolveLines} turns the
 * expanded lines into {@link NavPosInvoiceLineRow}. Read by {@link Reader}: the amounts stay exact decimals (a tree of
 * the default mapper holds doubles, 90.000 would become 90.0), whichever mapper reads the page.
 */
@JsonDeserialize(using = NavPosInvoiceRow.Reader.class)
public class NavPosInvoiceRow {

	public static final String NO = "No";
	public static final String CUSTOMER_NAME = "Sell_to_Customer_Name";
	public static final String DOCUMENT_DATE = "Document_Date";
	public static final String POSTING_DATE = "Posting_Date";
	public static final String CLIENT_FRANCHISE = "Client_Franchise";
	/** Read only when the page has it (the Happyness page of 2026-10 does not). */
	public static final String PRICES_INCLUDING_VAT = "Prices_Including_VAT";

	/** Exact decimals: 41.000 stays 41.000 (no double, no trailing zeros stripped). */
	private static final ObjectMapper DECIMALS = new ObjectMapper()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

	/** Reads a row field by field with {@link #DECIMALS}. */
	public static class Reader extends JsonDeserializer<NavPosInvoiceRow> {
		@Override
		public NavPosInvoiceRow deserialize(JsonParser parser, DeserializationContext context) throws IOException {
			JsonNode tree = DECIMALS.readTree(parser);
			NavPosInvoiceRow row = new NavPosInvoiceRow();
			if (tree != null) {
				tree.fields().forEachRemaining(field -> row.set(field.getKey(), field.getValue()));
			}
			return row;
		}
	}

	private final Map<String, JsonNode> fields = new LinkedHashMap<>();

	@JsonIgnore
	private List<NavPosInvoiceLineRow> lines = new ArrayList<>();

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

	/** True when the page answered this field (even with null). */
	public boolean has(String name) {
		return fields.containsKey(name);
	}

	/** The lines of the expanded property linesExpand (none when absent); returns this row. */
	public NavPosInvoiceRow resolveLines(String linesExpand) {
		JsonNode value = fields.get(linesExpand);
		lines = value == null || !value.isArray() ? new ArrayList<>()
				: DECIMALS.convertValue(value, new TypeReference<List<NavPosInvoiceLineRow>>() {
				});
		return this;
	}

	@JsonIgnore
	public List<NavPosInvoiceLineRow> getLines() {
		return lines;
	}
}
