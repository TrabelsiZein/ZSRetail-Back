package com.digithink.zsretail.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.connector.happyness.dto.NavPosInvoiceRow;
import com.digithink.zsretail.connector.zen.dto.ZenInvoiceRow;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boot 4 migration, step 1.9: an ERP invoice row keeps the decimals the ERP wrote, scale included (41.000 stays 41.000, 1.50
 * stays 1.50, 0.000 stays 0.000), and its fields in their order. Money is compared and written back from them. Run on the
 * deprecated JsonNodeFactory.withExactBigDecimals and fields(), then on JsonNodeFeature and properties() that replace them.
 */
class InvoiceRowDecimalsTest {

	private static final String ROW = "{\"No\":\"FV-1\",\"amount\":41.000,\"price\":1.50,\"zero\":0.000,\"big\":12345678901234.56789,"
			+ "\"whole\":7,\"text\":\"x\",\"flag\":true}";

	private static final List<String> ORDER = Arrays.asList("No", "amount", "price", "zero", "big", "whole", "text", "flag");

	@Test
	@DisplayName("ZEN's invoice row: every decimal with its scale, the fields in the ERP's order")
	void zen() throws Exception {
		ZenInvoiceRow row = new ObjectMapper().readValue(ROW, ZenInvoiceRow.class);
		assertEquals("41.000", row.getFields().get("amount").decimalValue().toPlainString());
		assertEquals("1.50", row.getFields().get("price").decimalValue().toPlainString());
		assertEquals("0.000", row.getFields().get("zero").decimalValue().toPlainString());
		assertEquals("12345678901234.56789", row.getFields().get("big").decimalValue().toPlainString());
		assertEquals("41.000", row.decimal("amount").toPlainString());
		assertEquals(ORDER, Arrays.asList(row.getFields().keySet().toArray(new String[0])));
	}

	@Test
	@DisplayName("The NAV POS pages' invoice row: the same")
	void navPosPages() throws Exception {
		NavPosInvoiceRow row = new ObjectMapper().readValue(ROW, NavPosInvoiceRow.class);
		assertEquals("41.000", row.getFields().get("amount").decimalValue().toPlainString());
		assertEquals("1.50", row.getFields().get("price").decimalValue().toPlainString());
		assertEquals("0.000", row.getFields().get("zero").decimalValue().toPlainString());
		assertEquals("12345678901234.56789", row.getFields().get("big").decimalValue().toPlainString());
		assertEquals(ORDER, Arrays.asList(row.getFields().keySet().toArray(new String[0])));
	}
}
