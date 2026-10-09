package com.digithink.zsretail.erp.navpospages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.erp.navpospages.client.NavPosPagesReadException;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesRestClient;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesConfig;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;

/**
 * ERP catalogue, step 5: the three reads against a mocked server (the RestTemplate of the connector, its GET-only
 * interceptor included): the URLs ($select, $filter, $orderby, $top), GET, the next link, an HTTP error, a timeout.
 */
class NavPosPagesRestClientTest {

	private NavPosPagesProperties properties;
	private RestTemplate template;
	private MockRestServiceServer server;
	private NavPosPagesRestClient client;

	@BeforeEach
	void setUp() {
		properties = NavPosPagesTestSupport.properties();
		template = NavPosPagesConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		client = new NavPosPagesRestClient(template, properties);
	}

	@Test
	@DisplayName("Categories: $select of the four fields, GET, the rows of the sample")
	void categories() {
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "ItemCategory?$select=Code,Description,Parent_Category,Type"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(NavPosPagesTestSupport.sample("ItemCategory"), MediaType.APPLICATION_JSON));
		List<NavPosCategoryRow> rows = client.readCategories();
		server.verify();
		assertEquals(50, rows.size());
		assertEquals("CAT-BEB", rows.get(0).getCode());
		assertEquals("Categorie", rows.get(0).getType());
		assertEquals("FAM-ACB-BEB", rows.get(2).getParentCategory());
	}

	@Test
	@DisplayName("Items: $filter on the stock point's Location_Code (a quote doubled), $select of the six fields, the next link followed")
	void itemsAndNextLink() {
		String first = NavPosPagesTestSupport.COMPANY_URL + "PointStockPOS?$filter=Location_Code%20eq%20'O''NEIL'"
				+ "&$select=Item_No,Variant_Code,Description,Unit_Price,Family,Subfamily";
		String next = NavPosPagesTestSupport.COMPANY_URL
				+ "PointStockPOS?$filter=Location_Code%20eq%20'O''NEIL'&$select=Item_No&$skiptoken='000002'";
		server.expect(requestTo(first)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
				"{\"value\":[{\"Item_No\":\"000001\",\"Variant_Code\":\"\",\"Description\":\"A   \",\"Unit_Price\":2.45,"
						+ "\"Family\":\"F\",\"Subfamily\":\"S\",\"Inventory\":3}],\"@odata.nextLink\":\"" + next + "\"}",
				MediaType.APPLICATION_JSON));
		server.expect(requestTo(next)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
				"{\"value\":[{\"Item_No\":\"000002\",\"Unit_Price\":10}]}", MediaType.APPLICATION_JSON));
		List<NavPosStockRow> rows = client.readItems(" O'NEIL ");
		server.verify();
		assertEquals(2, rows.size());
		assertEquals("A   ", rows.get(0).getDescription());
		assertEquals(0, new java.math.BigDecimal("2.45").compareTo(rows.get(0).getUnitPrice()));
		assertEquals("000002", rows.get(1).getItemNo());
	}

	@Test
	@DisplayName("Items of the sample (stock point DA-MG-GREM): 50 rows")
	void itemsSample() {
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "PointStockPOS?$filter=Location_Code%20eq%20'DA-MG-GREM'"
				+ "&$select=Item_No,Variant_Code,Description,Unit_Price,Family,Subfamily"))
				.andRespond(withSuccess(NavPosPagesTestSupport.sample("PointStockPOS"), MediaType.APPLICATION_JSON));
		assertEquals(50, client.readItems("DA-MG-GREM").size());
		server.verify();
	}

	@Test
	@DisplayName("Barcodes: one page after an entry number, $orderby and $top, never the next link")
	void barcodes() {
		properties.setBarcodePageSize(250);
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "ItemBarCodePOS?$filter=Entry_No%20gt%2020000"
				+ "&$orderby=Entry_No&$top=250&$select=Item_No,Cross_Reference_No,Entry_No"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(NavPosPagesTestSupport.sample("ItemBarCodePOS").replace("{\"@odata.context\"",
						"{\"@odata.nextLink\":\"http://bc.test/never\",\"@odata.context\""), MediaType.APPLICATION_JSON));
		List<NavPosBarcodeRow> rows = client.readBarcodesAfter(20000);
		server.verify(); // the next link was not followed
		assertEquals(50, rows.size());
		assertEquals(Long.valueOf(20659), rows.get(0).getEntryNo());
		assertEquals("0000016", rows.get(0).getCrossReferenceNo());
	}

	@Test
	@DisplayName("Step 6: the barcodes of some items, $filter on Item_No (quotes doubled), every row, next link followed")
	void barcodesOfItems() {
		String first = NavPosPagesTestSupport.COMPANY_URL + "ItemBarCodePOS?$filter=Item_No%20eq%20'000002'%20or%20Item_No"
				+ "%20eq%20'O''B'&$select=Item_No,Cross_Reference_No,Entry_No";
		String next = NavPosPagesTestSupport.COMPANY_URL + "ItemBarCodePOS?$skiptoken='2'";
		server.expect(requestTo(first)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
				"{\"value\":[{\"Item_No\":\"000002\",\"Cross_Reference_No\":\"000002\",\"Entry_No\":20661}],"
						+ "\"@odata.nextLink\":\"" + next + "\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(next)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
				"{\"value\":[{\"Item_No\":\"O'B\",\"Cross_Reference_No\":\"619\",\"Entry_No\":3}]}", MediaType.APPLICATION_JSON));
		List<NavPosBarcodeRow> rows = client.readBarcodesOfItems(java.util.Arrays.asList("000002", "O'B"));
		server.verify();
		assertEquals(2, rows.size());
		assertTrue(client.readBarcodesOfItems(new java.util.ArrayList<>()).isEmpty(), "no call without items");
		assertEquals(NavPosPagesTestSupport.COMPANY_URL + "PointStockPOS", client.pageUrl("PointStockPOS"));
	}

	// ─── Invoices from the ERP, step (a) ────────────────────────

	/** The lines expanded with their $select: the = inside the $expand value is sent encoded (%3D), as BC reads it. */
	static final String LINES = "$expand=FactureFranchiseSalesInvLines($select%3DDocument_No,Line_No,Type,No,Description,"
			+ "Quantity,Unit_of_Measure_Code,Unit_Price,Line_Discount_Percent,Line_Amount,Total_Amount_Excl_VAT,"
			+ "Total_VAT_Amount,Total_Amount_Incl_VAT)";
	static final String HEADER = "$select=No,Sell_to_Customer_No,Sell_to_Customer_Name,Document_Date,Posting_Date,"
			+ "Client_Franchise";

	/** The invoices URL of a filter (already encoded), with or without Prices_Including_VAT. */
	private static String invoicesUrl(String filter, int top, boolean withPricesIncludingVat) {
		return NavPosPagesTestSupport.COMPANY_URL + "FactureFranchise?$filter=" + filter + "&$orderby=No&$top=" + top
				+ "&" + HEADER + (withPricesIncludingVat ? ",Prices_Including_VAT" : "") + "&" + LINES;
	}

	private static String invoices(int from, int count, String nextLink) {
		StringBuilder body = new StringBuilder("{\"value\":[");
		for (int i = 0; i < count; i++) {
			body.append(i == 0 ? "" : ",").append("{\"No\":\"FVV26").append(String.format("%09d", from + i))
					.append("\",\"FactureFranchiseSalesInvLines\":[{\"Line_No\":10000,\"Type\":\"Item\",\"No\":\"A\"}]}");
		}
		body.append("]").append(nextLink == null ? "" : ",\"@odata.nextLink\":\"" + nextLink + "\"").append("}");
		return body.toString();
	}

	@Test
	@DisplayName("Invoices: startswith on the year and No gt the number (quotes doubled), $orderby, $top, $select, the lines"
			+ " expanded with their $select; the sample read with its lines")
	void invoicesUrlAndSample() {
		server.expect(requestTo(invoicesUrl("startswith(No,'FVV26')%20and%20No%20gt%20'FVV26000000100'", 50, true)))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(NavPosPagesTestSupport.sample("FactureFranchise"), MediaType.APPLICATION_JSON));
		List<NavPosInvoiceRow> rows = client.readInvoicesAfter("FVV26", "FVV26000000100");
		server.verify();
		assertEquals(2, rows.size());
		assertEquals("FVV26000000101", rows.get(0).text("No"));
		assertEquals("C-0001", rows.get(0).text("Sell_to_Customer_No"));
		assertEquals(6, rows.get(0).getLines().size());
		assertEquals("Item", rows.get(0).getLines().get(2).getType());
		assertEquals("true", rows.get(1).text("Prices_Including_VAT"));

		// First read of a year: startswith only; quotes doubled in both; the customer field once in $select
		server.reset();
		properties.getInvoices().setCustomerField("Sell_to_Customer_Name");
		properties.getInvoices().setMaxPerRun(7);
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "FactureFranchise?$filter=startswith(No,'F''V26')"
				+ "&$orderby=No&$top=7&$select=No,Sell_to_Customer_Name,Document_Date,Posting_Date,Client_Franchise,"
				+ "Prices_Including_VAT&" + LINES)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		assertTrue(client.readInvoicesAfter("F'V26", " ").isEmpty());
		server.reset();
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "FactureFranchise?$filter=startswith(No,'FVV26')"
				+ "%20and%20No%20gt%20'O''K'&$orderby=No&$top=7&$select=No,Sell_to_Customer_Name,Document_Date,"
				+ "Posting_Date,Client_Franchise,Prices_Including_VAT&" + LINES))
				.andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		assertTrue(client.readInvoicesAfter("FVV26", "O'K").isEmpty());
		server.verify();
	}

	@Test
	@DisplayName("Invoices: next links followed until max-per-run; the page after it never asked")
	void invoicesNextLinksAndMax() {
		properties.getInvoices().setMaxPerRun(3);
		String next1 = NavPosPagesTestSupport.COMPANY_URL + "FactureFranchise?$skiptoken='2'";
		String next2 = NavPosPagesTestSupport.COMPANY_URL + "FactureFranchise?$skiptoken='4'";
		server.expect(requestTo(invoicesUrl("startswith(No,'FVV26')", 3, true)))
				.andRespond(withSuccess(invoices(1, 2, next1), MediaType.APPLICATION_JSON));
		server.expect(requestTo(next1)).andRespond(withSuccess(invoices(3, 2, next2), MediaType.APPLICATION_JSON));
		List<NavPosInvoiceRow> rows = client.readInvoicesAfter("FVV26", null);
		server.verify(); // next2 was not asked for
		assertEquals(3, rows.size());
		assertEquals("FVV26000000003", rows.get(2).text("No"));
		assertEquals(1, rows.get(2).getLines().size());

		server.reset();
		server.expect(requestTo(invoicesUrl("startswith(No,'FVV26')", 3, true)))
				.andRespond(withSuccess(invoices(1, 1, next1), MediaType.APPLICATION_JSON));
		server.expect(requestTo(next1)).andRespond(withSuccess(invoices(2, 1, null), MediaType.APPLICATION_JSON));
		assertEquals(2, client.readInvoicesAfter("FVV26", null).size(), "fewer than the maximum: every page");
		server.verify();
	}

	@Test
	@DisplayName("Invoices: a page without Prices_Including_VAT (400 naming it) is read again without it, then never asked")
	void invoicesWithoutPricesIncludingVat() {
		String noProperty = "{\"error\":{\"code\":\"BadRequest\",\"message\":\"Could not find a property named"
				+ " 'Prices_Including_VAT' on type 'NAV.FactureFranchise'.  CorrelationId:  x.\"}}";
		server.expect(requestTo(invoicesUrl("startswith(No,'FVV26')", 50, true)))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(noProperty));
		server.expect(requestTo(invoicesUrl("startswith(No,'FVV26')", 50, false)))
				.andRespond(withSuccess(invoices(1, 1, null), MediaType.APPLICATION_JSON));
		server.expect(requestTo(invoicesUrl("startswith(No,'FVV25')", 50, false)))
				.andRespond(withSuccess(invoices(1, 1, null), MediaType.APPLICATION_JSON));
		assertEquals(1, client.readInvoicesAfter("FVV26", null).size());
		assertEquals(1, client.readInvoicesAfter("FVV25", null).size());
		server.verify();
	}

	@Test
	@DisplayName("Invoices: any other 400 (a customer field the page does not have) fails naming the page, no second read")
	void invoicesOtherError() {
		String noProperty = "{\"error\":{\"code\":\"BadRequest\",\"message\":\"Could not find a property named"
				+ " 'Sell_to_Customer_No' on type 'NAV.FactureFranchise'.\"}}";
		server.expect(requestTo(invoicesUrl("startswith(No,'FVV26')", 50, true)))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(noProperty));
		NavPosPagesReadException error = assertThrows(NavPosPagesReadException.class,
				() -> client.readInvoicesAfter("FVV26", null));
		server.verify();
		assertEquals("FactureFranchise", error.getPage());
		assertTrue(error.getMessage().contains("reading the page FactureFranchise failed: HTTP 400"), error.getMessage());
	}

	@Test
	@DisplayName("An HTTP error names the page and the status; a timeout names the page")
	void errors() {
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "PointStockPOS?$filter=Location_Code%20eq%20'FRANCHISE'"
				+ "&$select=Item_No,Variant_Code,Description,Unit_Price,Family,Subfamily"))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED));
		NavPosPagesReadException error = assertThrows(NavPosPagesReadException.class, () -> client.readItems("FRANCHISE"));
		assertEquals("PointStockPOS", error.getPage());
		assertTrue(error.getMessage().contains("reading the page PointStockPOS failed: HTTP 401"), error.getMessage());

		server.reset();
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "ItemCategory?$select=Code,Description,Parent_Category,Type"))
				.andRespond(request -> {
					throw new SocketTimeoutException("Read timed out");
				});
		NavPosPagesReadException timeout = assertThrows(NavPosPagesReadException.class, () -> client.readCategories());
		assertTrue(timeout.getMessage().contains("reading the page ItemCategory failed: no answer or timeout"),
				timeout.getMessage());
	}

	@Test
	@DisplayName("First invoice of the page: one GET, startswith the prefix, $orderby No, $top 1, $select No; an empty page gives null")
	void firstInvoiceNumber() {
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL
				+ "FactureFranchise?$filter=startswith(No,'FVV')&$orderby=No&$top=1&$select=No"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"value\":[{\"No\":\" FVV24000000009 \"}]}", MediaType.APPLICATION_JSON));
		assertEquals("FVV24000000009", client.readFirstInvoiceNumber("FVV"));
		server.verify();
		server.reset();
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL
				+ "FactureFranchise?$filter=startswith(No,'FVV')&$orderby=No&$top=1&$select=No"))
				.andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		assertNull(client.readFirstInvoiceNumber("FVV"));
		server.verify();
	}

	@Test
	@DisplayName("Any method other than GET is refused before it leaves: nothing reaches the server")
	void getOnly() {
		for (HttpMethod refused : new HttpMethod[] { HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE }) {
			IllegalStateException error = assertThrows(IllegalStateException.class,
					() -> template.exchange(NavPosPagesTestSupport.COMPANY_URL + "ItemCategory", refused, null, String.class));
			assertTrue(error.getMessage().startsWith("The navpospages connector only reads from the ERP (GET): " + refused),
					error.getMessage());
		}
		server.verify(); // no request expected, none made
	}
}
