package com.digithink.zsretail.connector.zen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.zen.config.ZenConfig;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.dto.ZenInvoiceRow;

/**
 * Item variants (ZEN), step 9: the address and the filters of the invoices read, against a mock server (no live call):
 * NAV's standard OData V4 page under Company('ESP'), never the custom API; per year prefix after a number, only the
 * stores' customers; the header and line fields selected; the next links followed up to page-size; by number 20 per GET;
 * the first invoice of the stores; no customer, no GET.
 */
class ZenInvoiceClientTest {

	static final String PAGE = "http://nav.test:7048/BC280/ODataV4/Company('ESP')/SalesInvoice";
	static final String SELECT = "$select=No,Sell_to_Customer_No,Sell_to_Customer_Name,Document_Date,Posting_Date,"
			+ "Cancelled,Corrective";
	/** The lines expanded with their $select: the = inside the $expand value is sent encoded (%3D), as Happyness's. */
	static final String LINES = "$expand=SalesInvoiceSalesInvLines($select%3DDocument_No,Line_No,Type,No,Variant_Code,"
			+ "Description,Quantity,Unit_of_Measure_Code,Unit_Price,Line_Discount_Percent,Line_Amount,Total_Amount_Excl_VAT,"
			+ "Total_VAT_Amount,Total_Amount_Incl_VAT)";
	static final List<String> STORES = Arrays.asList("4110001", "4110002");

	private ZenProperties properties;
	private MockRestServiceServer server;
	private ZenRestClient client;

	@BeforeEach
	void setUp() {
		properties = new ZenProperties();
		properties.setBaseUrl("http://nav.test:7048/BC280");
		properties.setCompany("ESP");
		properties.setCompanyId("1d6c4f00-6ac9-f111-9db8-ad3d0c589c9c"); // the custom API's: not used for the invoices
		properties.setUsername("user");
		properties.setPassword("secret");
		properties.setDefaultVat(19);
		RestTemplate template = ZenConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		client = new ZenRestClient(template, properties);
	}

	private static String invoice(String number) {
		return "{\"No\":\"" + number + "\",\"Sell_to_Customer_No\":\"4110001\",\"Cancelled\":false,\"Corrective\":false,"
				+ "\"SalesInvoiceSalesInvLines\":[{\"Line_No\":10000,\"Type\":\"Item\",\"No\":\"32ZFCHD21\",\"Variant_Code\":"
				+ "\"001\",\"Quantity\":1,\"Line_Amount\":90.000}]}";
	}

	private static String numbers(List<ZenInvoiceRow> rows) {
		return rows.stream().map(ZenInvoiceRow::getNumber).collect(Collectors.joining(","));
	}

	@Test
	@DisplayName("The filters: the year prefix, after a number when there is one, the stores' customers in brackets (upper case, once, quotes doubled)")
	void filters() {
		assertEquals("startswith(No,'FVE26') and No gt 'FVE2600001' and (Sell_to_Customer_No eq '4110001' or"
				+ " Sell_to_Customer_No eq '4110002')", ZenRestClient.invoicesAfterFilter("FVE26", "FVE2600001", STORES));
		assertEquals("startswith(No,'FVE26') and (Sell_to_Customer_No eq '4110001' or Sell_to_Customer_No eq '4110002')",
				ZenRestClient.invoicesAfterFilter("FVE26", null, STORES), "a first read: startswith only");
		assertEquals("startswith(No,'FVE26') and (Sell_to_Customer_No eq '4110001' or Sell_to_Customer_No eq '4110002')",
				ZenRestClient.invoicesAfterFilter("FVE26", " ", STORES));
		assertEquals("(Sell_to_Customer_No eq 'C-01' or Sell_to_Customer_No eq 'O''NEIL')",
				ZenRestClient.customersFilter(Arrays.asList(" c-01 ", "C-01", "", null, "o'neil")));
		assertEquals("(Sell_to_Customer_No eq '4110001')", ZenRestClient.customersFilter(Collections.singleton("4110001")));
		assertEquals("startswith(No,'FVE') and (Sell_to_Customer_No eq '4110001' or Sell_to_Customer_No eq '4110002')",
				ZenRestClient.firstInvoiceFilter("FVE", STORES));
		assertEquals("No eq 'FVE2600001' or No eq 'FVE''26'", ZenRestClient.numbersFilter(Arrays.asList("FVE2600001",
				"FVE'26")));
	}

	@Test
	@DisplayName("The page: base-url / ODataV4 / Company('ESP') / SalesInvoice (a quote doubled), or a whole address; the OData path a setting")
	void pageAddress() {
		assertEquals(PAGE, properties.invoicesPageAddress());
		assertEquals(PAGE, client.invoicesPageUrl());
		properties.setCompany("O'Neil SA");
		assertEquals("http://nav.test:7048/BC280/ODataV4/Company('O''Neil SA')/SalesInvoice", properties.invoicesPageAddress());
		properties.setBaseUrl("http://nav.test:7048/BC280/");
		properties.getInvoices().setOdataPath("/OData/");
		properties.setCompany("ESP");
		assertEquals("http://nav.test:7048/BC280/OData/Company('ESP')/SalesInvoice", properties.invoicesPageAddress());
		properties.getInvoices().setPage("https://nav.test/ODataV4/Company('ESP')/PostedSalesInvoices");
		assertEquals("https://nav.test/ODataV4/Company('ESP')/PostedSalesInvoices", properties.invoicesPageAddress());
	}

	@Test
	@DisplayName("A year after a number: the whole address (filter, by number, $top page-size, header and line fields), the next link followed, the lines resolved")
	void readAfter() {
		String next = PAGE + "?$skiptoken=2";
		server.expect(requestTo(PAGE + "?$filter=startswith(No,'FVE26')%20and%20No%20gt%20'FVE2600001'%20and"
				+ "%20(Sell_to_Customer_No%20eq%20'4110001'%20or%20Sell_to_Customer_No%20eq%20'4110002')&$orderby=No&$top=50&"
				+ SELECT + "&" + LINES)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"value\":[" + invoice("FVE2600002") + "],\"@odata.nextLink\":\"" + next + "\"}",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(next)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"value\":[" + invoice("FVE2600003") + "]}", MediaType.APPLICATION_JSON));

		List<ZenInvoiceRow> rows = client.readInvoicesAfter("FVE26", "FVE2600001", STORES);

		server.verify();
		assertEquals("FVE2600002,FVE2600003", numbers(rows));
		assertEquals("32ZFCHD21", rows.get(0).getLines().get(0).getNo());
		assertEquals("001", rows.get(0).getLines().get(0).getVariantCode());
		assertEquals("90.000", rows.get(0).getLines().get(0).getLineAmount().toPlainString(), "exact decimals");
	}

	@Test
	@DisplayName("At most page-size invoices: no next link followed past it, the extra rows of a page cut")
	void pageSize() {
		properties.getInvoices().setPageSize(2);
		server.expect(requestTo(PAGE + "?$filter=startswith(No,'FVE26')%20and%20(Sell_to_Customer_No%20eq%20'4110001'"
				+ "%20or%20Sell_to_Customer_No%20eq%20'4110002')&$orderby=No&$top=2&" + SELECT + "&" + LINES))
				.andRespond(withSuccess("{\"value\":[" + invoice("FVE2600001") + "," + invoice("FVE2600002") + ","
						+ invoice("FVE2600003") + "],\"@odata.nextLink\":\"" + PAGE + "?$skiptoken=3\"}",
						MediaType.APPLICATION_JSON));

		assertEquals("FVE2600001,FVE2600002", numbers(client.readInvoicesAfter("FVE26", null, STORES)));
		server.verify();
	}

	@Test
	@DisplayName("No customer: no GET, nothing read")
	void noCustomer() {
		assertTrue(client.readInvoicesAfter("FVE26", null, new ArrayList<>()).isEmpty());
		assertTrue(client.readInvoicesAfter("FVE26", null, null).isEmpty());
		assertNull(client.readFirstInvoiceNumber("FVE", new TreeSet<>()));
		server.verify();
	}

	@Test
	@DisplayName("The first invoice of the stores: one GET, $top=1, $select=No; none on the page: null")
	void firstInvoice() {
		String filter = "$filter=startswith(No,'FVE')%20and%20(Sell_to_Customer_No%20eq%20'4110001'%20or"
				+ "%20Sell_to_Customer_No%20eq%20'4110002')&$orderby=No&$top=1&$select=No";
		server.expect(requestTo(PAGE + "?" + filter)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"value\":[{\"No\":\" FVE2500007 \"}]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(PAGE + "?" + filter)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

		assertEquals("FVE2500007", client.readFirstInvoiceNumber("FVE", STORES));
		assertNull(client.readFirstInvoiceNumber("FVE", STORES));
		server.verify();
	}

	@Test
	@DisplayName("By number: whatever the customer, 20 numbers per GET, $top the numbers asked")
	void byNumbers() {
		List<String> asked = new ArrayList<>();
		for (int i = 1; i <= 21; i++) {
			asked.add(String.format("FVE26%05d", i));
		}
		String first = asked.subList(0, 20).stream().map(number -> "No%20eq%20'" + number + "'")
				.collect(Collectors.joining("%20or%20"));
		server.expect(requestTo(PAGE + "?$filter=" + first + "&$orderby=No&$top=20&" + SELECT + "&" + LINES))
				.andRespond(withSuccess("{\"value\":[" + invoice("FVE2600001") + "]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(PAGE + "?$filter=No%20eq%20'FVE2600021'&$orderby=No&$top=1&" + SELECT + "&" + LINES))
				.andRespond(withSuccess("{\"value\":[" + invoice("FVE2600021") + "]}", MediaType.APPLICATION_JSON));

		assertEquals("FVE2600001,FVE2600021", numbers(client.readInvoicesByNumbers(asked)));
		server.verify();
	}

	@Test
	@DisplayName("An HTTP error names the page and the status")
	void httpError() {
		server.expect(requestTo(PAGE + "?$filter=No%20eq%20'FVE2600001'&$orderby=No&$top=1&" + SELECT + "&" + LINES))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));
		ZenReadException failure = assertThrows(ZenReadException.class,
				() -> client.readInvoicesByNumbers(Collections.singletonList("FVE2600001")));
		assertEquals("SalesInvoice", failure.getPage());
		assertTrue(failure.getMessage().startsWith("zen: reading the page SalesInvoice failed: HTTP 404"),
				failure.getMessage());
	}
}
