package com.digithink.zsretail.connector.zen.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * Item variants (ZEN), step 9: the invoices of salesInvoices (ZEN's franchise API) to ErpSupplyInvoiceDTO, on the
 * sample saved by Zein (src/test/resources/zen/salesInvoices.json, ZEN's test NAV, 2026-10-24: one invoice, twelve
 * variants of one item and the G/L rounding line at -0.002) and on rows written here for what the sample does not have
 * (a blank variant, a cancelled or corrective invoice once the page gives the flags, comment lines, a currency).
 */
class ZenMapperInvoicesTest {

	static final String LINES = "salesInvoiceLines";
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
		try (InputStream in = ZenMapperInvoicesTest.class.getResourceAsStream("/zen/salesInvoices.json")) {
			List<ZenInvoiceRow> rows = JSON.readValue(in, new TypeReference<ZenCollection<ZenInvoiceRow>>() {
			}).getValue();
			rows.forEach(row -> row.resolveLines(LINES));
			return rows;
		}
	}

	/** One invoice of the page, its totals 90.000 / 107.100: its other header fields, then its lines as JSON objects. */
	static String invoice(String number, String header, String... lines) {
		return "{\"number\":\"" + number + "\",\"sellToCustomerNo\":\"4110001\",\"sellToCustomerName\":\"Point de vente"
				+ " El Kef\",\"documentDate\":\"2026-10-22\",\"postingDate\":\"2026-10-23\",\"currencyCode\":\"\","
				+ "\"amount\":90.000,\"amountIncludingVAT\":107.100" + (header.isEmpty() ? "" : "," + header) + ",\""
				+ LINES + "\":[" + String.join(",", lines) + "]}";
	}

	static String line(int lineNo, String type, String no, String variant, String quantity, String amount) {
		return "{\"lineNo\":" + lineNo + ",\"lineType\":\"" + type + "\",\"number\":\"" + no + "\",\"variantCode\":\""
				+ variant + "\",\"description\":\"CHEMISE\",\"quantity\":" + quantity + ",\"unitOfMeasureCode\":\"PCS\","
				+ "\"unitPrice\":20.000,\"lineDiscountPercent\":0,\"lineAmount\":" + amount + ",\"amount\":" + amount
				+ ",\"vatPercent\":19}";
	}

	static String page(String... invoices) {
		return "{\"value\":[" + String.join(",", invoices) + "]}";
	}

	private static List<String> itemCodes(ErpSupplyInvoiceDTO invoice) {
		return invoice.getLines().stream().map(ErpSupplyInvoiceDTO.Line::getItemCode).collect(Collectors.toList());
	}

	@Test
	@DisplayName("The sample: twelve ITEM lines number~variantCode, the rounding line OTHER, the totals of the header, no warning")
	void sampleInvoice() throws IOException {
		ErpRead<ErpSupplyInvoiceDTO> read = mapper.invoices(sample());
		assertEquals(1, read.getRead());
		assertEquals(Collections.emptyMap(), read.getLeftOut());
		assertEquals(Collections.emptyMap(), read.getNotes());
		ErpSupplyInvoiceDTO invoice = read.getRows().get(0);
		assertEquals("FVE2600002", invoice.getNumber());
		assertEquals("4110001", invoice.getCustomerNo());
		assertEquals("Point de vente El Kef", invoice.getCustomerName());
		assertEquals(LocalDate.of(2026, 10, 24), invoice.getDocumentDate());
		assertEquals(LocalDate.of(2026, 10, 24), invoice.getPostingDate());
		assertNull(invoice.getPricesIncludingVat(), "ZEN's prices are before VAT: the page has no such field");
		assertNull(invoice.getYearPrefix(), "set by the run, from the year read");
		assertEquals("133.798", invoice.getTotalExclVat().toPlainString());
		assertEquals("159.22", invoice.getTotalInclVat().toPlainString());
		assertEquals("25.422", invoice.getTotalVat().toPlainString(), "the difference");
		List<String> codes = new ArrayList<>();
		for (int variant = 1; variant <= 12; variant++) {
			codes.add(String.format("43ZBPND01~%03d", variant));
		}
		codes.add(null);
		assertEquals(codes, itemCodes(invoice), "the code the catalogue sync writes; the G/L line has none");
		ErpSupplyInvoiceDTO.Line first = invoice.getLines().get(0);
		assertEquals(Integer.valueOf(10000), first.getLineNo());
		assertEquals(ErpSupplyInvoiceDTO.LineType.ITEM, first.getType());
		assertEquals("JEANS", first.getDescription());
		assertEquals(0, first.getQuantity().compareTo(BigDecimal.ONE));
		assertEquals("PCS", first.getUnitOfMeasure());
		assertEquals(0, first.getUnitPrice().compareTo(new BigDecimal("12")));
		assertEquals(0, first.getLineDiscountPercent().compareTo(new BigDecimal("15")));
		assertEquals("10.2", first.getLineAmount().toPlainString());
		ErpSupplyInvoiceDTO.Line rounding = invoice.getLines().get(12);
		assertEquals(Integer.valueOf(130000), rounding.getLineNo());
		assertEquals(ErpSupplyInvoiceDTO.LineType.OTHER, rounding.getType(), "G_x002F_L_x0020_Account with an amount");
		assertEquals(0, rounding.getLineAmount().compareTo(new BigDecimal("-0.002")),
				"NAV writes it -0.00200000000000000000: kept as sent");
		assertTrue(invoice.getWarnings().isEmpty(), "the lines add up to the amount: " + invoice.getWarnings());
	}

	@Test
	@DisplayName("Amounts exact (90.000 stays 90.000), a blank variant gives number alone, blank and G/L lines at 0 left out, a G/L line with an amount OTHER")
	void linesAndAmounts() throws IOException {
		ErpRead<ErpSupplyInvoiceDTO> read = mapper.invoices(rows(page(invoice("FVE2600002", "",
				line(10000, "Item", "32ZFCHD21", "001", "2", "40.000"), line(20000, "Item", " 42ZTAPOH04 ", " ", "1.5", "30.000"),
				line(30000, "_x0020_", "", "", "0", "0"), line(40000, "G_x002F_L_x0020_Account", "7070", "", "1", "20.000"),
				line(50000, "G_x002F_L_x0020_Account", "7071", "", "1", "0")))));
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
	@DisplayName("Once the page gives them: cancelled left out and counted; corrective read, with a warning and a note; a blank number left out")
	void cancelledAndCorrective() throws IOException {
		ErpRead<ErpSupplyInvoiceDTO> read = mapper.invoices(rows(page(
				invoice("FVE2600003", "\"cancelled\":true", line(10000, "Item", "32ZFCHD21", "001", "1", "90")),
				invoice("FVE2600004", "\"corrective\":true", line(10000, "Item", "32ZFCHD21", "001", "1", "90")),
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
	@DisplayName("Warnings: lines off the header's amount, more than 3 decimals, an Item line without number, no line, a currency")
	void warnings() throws IOException {
		ErpRead<ErpSupplyInvoiceDTO> read = mapper.invoices(rows(page(
				invoice("FVE2600005", "", line(10000, "Item", "32ZFCHD21", "001", "1.2345", "80.000"),
						line(20000, "Item", "", "", "1", "5")),
				invoice("FVE2600006", ""),
				invoice("FVE2600007", "\"currencyCode\":\"EUR\"", line(10000, "Item", "32ZFCHD21", "001", "1", "90.000")))));
		List<String> first = read.getRows().get(0).getWarnings();
		assertEquals(Arrays.asList("line 10000: quantity 1.2345 of item 32ZFCHD21~001 has more than 3 decimals",
				"line 20000: an Item line without an item number", "the lines add up to 85.000, amount is 90.000"), first);
		assertEquals(ErpSupplyInvoiceDTO.LineType.OTHER, read.getRows().get(0).getLines().get(1).getType());
		assertEquals(Arrays.asList(ZenMapper.NO_LINE, "the lines add up to 0, amount is 90.000"),
				read.getRows().get(1).getWarnings());
		assertEquals("90.000", read.getRows().get(1).getTotalExclVat().toPlainString(), "the totals are the header's");
		assertEquals(Arrays.asList(ZenMapper.FOREIGN_CURRENCY + " (currencyCode EUR)"), read.getRows().get(2).getWarnings());
		assertEquals(Integer.valueOf(1), read.getNotes().get(ZenMapper.FOREIGN_CURRENCY));
		assertEquals(Integer.valueOf(3), read.getNotes().get(ZenMapper.WITH_WARNINGS));
	}

	@Test
	@DisplayName("An invoice discount: the header's amount below the lines' lineAmount, a warning; no amount on the header: no totals, no check")
	void invoiceDiscountAndNoTotals() throws IOException {
		ErpRead<ErpSupplyInvoiceDTO> read = mapper.invoices(rows(page(
				invoice("FVE2600008", "", line(10000, "Item", "32ZFCHD21", "001", "1", "100.000")),
				"{\"number\":\"FVE2600009\",\"" + LINES + "\":[" + line(10000, "Item", "32ZFCHD21", "001", "1", "90") + "]}")));
		assertEquals(Arrays.asList("the lines add up to 100.000, amount is 90.000"), read.getRows().get(0).getWarnings());
		ErpSupplyInvoiceDTO none = read.getRows().get(1);
		assertNull(none.getTotalExclVat());
		assertNull(none.getTotalVat());
		assertNull(none.getTotalInclVat());
		assertTrue(none.getWarnings().isEmpty(), none.getWarnings().toString());
	}
}
