package com.digithink.zsretail.connector.zen.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.connector.zen.dto.ZenCollection;
import com.digithink.zsretail.connector.zen.dto.ZenInvoiceRow;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpSupplyInvoiceDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Item variants (ZEN), step 9: the invoices of NAV's page SalesInvoice to ErpSupplyInvoiceDTO, on the sample saved by
 * Zein (src/test/resources/zen/SalesInvoice.json, ZEN's test NAV, 2026-10-23: one invoice, five variants of one item,
 * amounts 0) and on rows written here for what the sample does not have (amounts, a blank variant, a cancelled or
 * corrective invoice, comment and G/L lines).
 */
class ZenMapperInvoicesTest {

	static final String LINES = "SalesInvoiceSalesInvLines";
	private static final ObjectMapper JSON = new ObjectMapper();

	private final ZenMapper mapper = new ZenMapper(19);

	/** The rows of an answer of the page, read as the client reads them, their lines resolved. */
	static List<ZenInvoiceRow> rows(String json) throws IOException {
		List<ZenInvoiceRow> rows = JSON.readValue(json, new TypeReference<ZenCollection<ZenInvoiceRow>>() {
		}).getValue();
		rows.forEach(row -> row.resolveLines(LINES));
		return rows;
	}

	static List<ZenInvoiceRow> sample() throws IOException {
		try (InputStream in = ZenMapperInvoicesTest.class.getResourceAsStream("/zen/SalesInvoice.json")) {
			List<ZenInvoiceRow> rows = JSON.readValue(in, new TypeReference<ZenCollection<ZenInvoiceRow>>() {
			}).getValue();
			rows.forEach(row -> row.resolveLines(LINES));
			return rows;
		}
	}

	/** One invoice of the page: its header fields, then its lines as JSON objects. */
	static String invoice(String number, String header, String... lines) {
		return "{\"No\":\"" + number + "\",\"Sell_to_Customer_No\":\"4110001\",\"Sell_to_Customer_Name\":\"Point de vente"
				+ " El Kef\",\"Document_Date\":\"2026-10-22\",\"Posting_Date\":\"2026-10-23\",\"Cancelled\":false,"
				+ "\"Corrective\":false" + (header.isEmpty() ? "" : "," + header) + ",\"" + LINES + "\":["
				+ String.join(",", lines) + "]}";
	}

	static String line(int lineNo, String type, String no, String variant, String quantity, String amount) {
		return "{\"Line_No\":" + lineNo + ",\"Type\":\"" + type + "\",\"No\":\"" + no + "\",\"Variant_Code\":\""
				+ variant + "\",\"Description\":\"CHEMISE\",\"Quantity\":" + quantity + ",\"Unit_of_Measure_Code\":\"PCS\","
				+ "\"Unit_Price\":20.000,\"Line_Discount_Percent\":0,\"Line_Amount\":" + amount + ",\"VAT_Percent\":19,"
				+ "\"Unit_Cost_LCY\":7,\"Total_Amount_Excl_VAT\":90.000,\"Total_VAT_Amount\":17.100,"
				+ "\"Total_Amount_Incl_VAT\":107.100}";
	}

	static String page(String... invoices) {
		return "{\"value\":[" + String.join(",", invoices) + "]}";
	}

	private static List<String> itemCodes(ErpSupplyInvoiceDTO invoice) {
		return invoice.getLines().stream().map(ErpSupplyInvoiceDTO.Line::getItemCode).collect(Collectors.toList());
	}

	@Test
	@DisplayName("The sample: one invoice of the store's customer, the five variants as five ITEM lines No~Variant_Code, the totals of the first line")
	void sampleInvoice() throws IOException {
		ErpRead<ErpSupplyInvoiceDTO> read = mapper.invoices(sample());
		assertEquals(1, read.getRead());
		assertEquals(Collections.emptyMap(), read.getLeftOut());
		assertEquals(Collections.emptyMap(), read.getNotes());
		ErpSupplyInvoiceDTO invoice = read.getRows().get(0);
		assertEquals("FVE2600001", invoice.getNumber());
		assertEquals("4110001", invoice.getCustomerNo());
		assertEquals("Point de vente El Kef", invoice.getCustomerName());
		assertEquals(LocalDate.of(2026, 10, 22), invoice.getDocumentDate());
		assertEquals(LocalDate.of(2026, 10, 22), invoice.getPostingDate());
		assertNull(invoice.getPricesIncludingVat(), "ZEN's prices are before VAT: the page has no such field");
		assertNull(invoice.getYearPrefix(), "set by the run, from the year read");
		assertEquals(0, invoice.getTotalExclVat().signum());
		assertEquals(0, invoice.getTotalVat().signum());
		assertEquals(0, invoice.getTotalInclVat().signum());
		assertEquals(Arrays.asList("32ZFCHD21~001", "32ZFCHD21~002", "32ZFCHD21~003", "32ZFCHD21~004", "32ZFCHD21~005"),
				itemCodes(invoice), "the code the catalogue sync writes");
		ErpSupplyInvoiceDTO.Line first = invoice.getLines().get(0);
		assertEquals(Integer.valueOf(10000), first.getLineNo());
		assertEquals(ErpSupplyInvoiceDTO.LineType.ITEM, first.getType());
		assertEquals("CHEMISE", first.getDescription());
		assertEquals(0, first.getQuantity().compareTo(BigDecimal.ONE));
		assertEquals("PCS", first.getUnitOfMeasure());
		assertEquals(0, first.getUnitPrice().signum());
		assertEquals(0, first.getLineDiscountPercent().signum());
		assertEquals(0, first.getLineAmount().signum(), "an item at price 0 is kept");
		assertTrue(invoice.getWarnings().isEmpty(), invoice.getWarnings().toString());
	}

	@Test
	@DisplayName("Amounts exact (90.000 stays 90.000), a blank variant gives No alone, comments and G/L lines at 0 left out, a G/L line with an amount OTHER")
	void linesAndAmounts() throws IOException {
		ErpRead<ErpSupplyInvoiceDTO> read = mapper.invoices(rows(page(invoice("FVE2600002", "",
				line(10000, "Item", "32ZFCHD21", "001", "2", "40.000"), line(20000, "Item", " 42ZTAPOH04 ", " ", "1.5", "30.000"),
				line(30000, " ", "", "", "0", "0"), line(40000, "G/L Account", "7070", "", "1", "20.000"),
				line(50000, "G/L Account", "7071", "", "1", "0")))));
		ErpSupplyInvoiceDTO invoice = read.getRows().get(0);
		assertEquals(Arrays.asList("32ZFCHD21~001", "42ZTAPOH04", null), itemCodes(invoice));
		assertEquals(Arrays.asList(ErpSupplyInvoiceDTO.LineType.ITEM, ErpSupplyInvoiceDTO.LineType.ITEM,
				ErpSupplyInvoiceDTO.LineType.OTHER),
				invoice.getLines().stream().map(ErpSupplyInvoiceDTO.Line::getType).collect(Collectors.toList()));
		assertEquals("90.000", invoice.getTotalExclVat().toPlainString());
		assertEquals("17.100", invoice.getTotalVat().toPlainString());
		assertEquals("107.100", invoice.getTotalInclVat().toPlainString());
		assertEquals("1.5", invoice.getLines().get(1).getQuantity().toPlainString(), "a decimal quantity as sent");
		assertEquals("40.000", invoice.getLines().get(0).getLineAmount().toPlainString());
		assertEquals(Integer.valueOf(1), read.getLeftOut().get(ZenMapper.COMMENT_LINE));
		assertEquals(Integer.valueOf(1), read.getLeftOut().get(ZenMapper.ZERO_OTHER_LINE));
		assertTrue(invoice.getWarnings().isEmpty(), "the lines add up to 90.000: " + invoice.getWarnings());
	}

	@Test
	@DisplayName("Cancelled: left out and counted; corrective: read, with a warning and a note; a blank number left out")
	void cancelledAndCorrective() throws IOException {
		ErpRead<ErpSupplyInvoiceDTO> read = mapper.invoices(rows(page(
				invoice("FVE2600003", "\"Cancelled\":true", line(10000, "Item", "32ZFCHD21", "001", "1", "90")),
				invoice("FVE2600004", "\"Corrective\":true", line(10000, "Item", "32ZFCHD21", "001", "1", "90")),
				invoice(" ", "", line(10000, "Item", "32ZFCHD21", "001", "1", "90")))));
		assertEquals(3, read.getRead());
		assertEquals(Arrays.asList("FVE2600004"),
				read.getRows().stream().map(ErpSupplyInvoiceDTO::getNumber).collect(Collectors.toList()));
		assertEquals(Integer.valueOf(1), read.getLeftOut().get(ZenMapper.CANCELLED));
		assertEquals(Integer.valueOf(1), read.getLeftOut().get(ZenMapper.BLANK_NUMBER));
		assertEquals(Arrays.asList(ZenMapper.CORRECTIVE), read.getRows().get(0).getWarnings());
		assertEquals(Integer.valueOf(1), read.getNotes().get(ZenMapper.CORRECTIVE));
		assertEquals(Integer.valueOf(1), read.getNotes().get(ZenMapper.WITH_WARNINGS));
	}

	@Test
	@DisplayName("Warnings as Happyness: lines off the total, more than 3 decimals, an Item line without number, no line")
	void warnings() throws IOException {
		ErpRead<ErpSupplyInvoiceDTO> read = mapper.invoices(rows(page(
				invoice("FVE2600005", "", line(10000, "Item", "32ZFCHD21", "001", "1.2345", "80.000"),
						line(20000, "Item", "", "", "1", "5")),
				invoice("FVE2600006", ""))));
		List<String> first = read.getRows().get(0).getWarnings();
		assertEquals(Arrays.asList("line 10000: quantity 1.2345 of item 32ZFCHD21~001 has more than 3 decimals",
				"line 20000: an Item line without an item number", "the lines add up to 85.000, Total_Amount_Excl_VAT is 90.000"),
				first);
		assertEquals(ErpSupplyInvoiceDTO.LineType.OTHER, read.getRows().get(0).getLines().get(1).getType());
		assertEquals(Arrays.asList(ZenMapper.NO_LINE), read.getRows().get(1).getWarnings());
		assertNull(read.getRows().get(1).getTotalExclVat());
		assertEquals(Integer.valueOf(2), read.getNotes().get(ZenMapper.WITH_WARNINGS));
	}
}
