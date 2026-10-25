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
 * salesInvoices of ZEN's franchise API under companies(id), the id of the zsretail API; per year prefix after a number,
 * only the stores' customers; no $select on the header, the line fields selected; the next links followed up to
 * page-size; by number 20 per GET; the first invoice of the stores; no customer, no GET.
 */
class ZenInvoiceClientTest {

	static final String COMPANY_ID = "1d6c4f00-6ac9-f111-9db8-ad3d0c589c9c";
	static final String PAGE = "http://nav.test:7048/BC280/api/sat/franchise/v1.0/companies(" + COMPANY_ID
			+ ")/salesInvoices";
	/** The lines expanded with their $select: the = inside the $expand value is sent encoded (%3D), as Happyness's. */
	static final String LINES = "$expand=salesInvoiceLines($select%3DdocumentNo,lineNo,lineType,number,variantCode,"
			+ "description,quantity,unitOfMeasureCode,unitPrice,lineDiscountPercent,lineAmount)";
	static final List<String> STORES = Arrays.asList("4110001", "4110002");
	static final String CUSTOMERS = "(sellToCustomerNo%20eq%20'4110001'%20or%20sellToCustomerNo%20eq%20'4110002')";

	private ZenProperties properties;
	private MockRestServiceServer server;
	private ZenRestClient client;

	@BeforeEach
	void setUp() {
		properties = new ZenProperties();
		properties.setBaseUrl("http://nav.test:7048/BC280");
		properties.setCompany("ESP");
		properties.setCompanyId(COMPANY_ID); // the zsretail API's: the same company on the franchise API
		properties.setUsername("user");
		properties.setPassword("secret");
		properties.setDefaultVat(19);
		RestTemplate template = ZenConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		client = new ZenRestClient(template, properties);
	}

	private static String invoice(String number) {
		return "{\"number\":\"" + number + "\",\"sellToCustomerNo\":\"4110001\",\"amount\":90.000,"
				+ "\"salesInvoiceLines\":[{\"lineNo\":10000,\"lineType\":\"Item\",\"number\":\"32ZFCHD21\",\"variantCode\":"
				+ "\"001\",\"quantity\":1,\"lineAmount\":90.000}]}";
	}

	private static String numbers(List<ZenInvoiceRow> rows) {
		return rows.stream().map(ZenInvoiceRow::getNumber).collect(Collectors.joining(","));
	}

	@Test
	@DisplayName("The filters: the year prefix, after a number when there is one, the stores' customers in brackets (upper case, once, quotes doubled)")
	void filters() {
		assertEquals("startswith(number,'FVE26') and number gt 'FVE2600001' and (sellToCustomerNo eq '4110001' or"
				+ " sellToCustomerNo eq '4110002')", ZenRestClient.invoicesAfterFilter("FVE26", "FVE2600001", STORES));
		assertEquals("startswith(number,'FVE26') and (sellToCustomerNo eq '4110001' or sellToCustomerNo eq '4110002')",
				ZenRestClient.invoicesAfterFilter("FVE26", null, STORES), "a first read: startswith only");
		assertEquals("startswith(number,'FVE26') and (sellToCustomerNo eq '4110001' or sellToCustomerNo eq '4110002')",
				ZenRestClient.invoicesAfterFilter("FVE26", " ", STORES));
		assertEquals("(sellToCustomerNo eq 'C-01' or sellToCustomerNo eq 'O''NEIL')",
				ZenRestClient.customersFilter(Arrays.asList(" c-01 ", "C-01", "", null, "o'neil")));
		assertEquals("(sellToCustomerNo eq '4110001')", ZenRestClient.customersFilter(Collections.singleton("4110001")));
		assertEquals("startswith(number,'FVE') and (sellToCustomerNo eq '4110001' or sellToCustomerNo eq '4110002')",
				ZenRestClient.firstInvoiceFilter("FVE", STORES));
		assertEquals("number eq 'FVE2600001' or number eq 'FVE''26'", ZenRestClient.numbersFilter(Arrays.asList(
				"FVE2600001", "FVE'26")));
	}

	@Test
	@DisplayName("The page: base-url / api/sat/franchise/v1.0 / companies(id) / salesInvoices, the API path a setting, or a whole address")
	void pageAddress() {
		assertEquals(PAGE, properties.invoicesPageAddress(COMPANY_ID));
		assertEquals(PAGE, client.invoicesPageUrl());
		properties.setBaseUrl("http://nav.test:7048/BC280/");
		properties.getInvoices().setApiPath("/api/zen/franchise/v2.0/");
		assertEquals("http://nav.test:7048/BC280/api/zen/franchise/v2.0/companies(" + COMPANY_ID + ")/salesInvoices",
				properties.invoicesPageAddress(COMPANY_ID));
		properties.getInvoices().setPage("https://nav.test/api/sat/franchise/v1.0/companies(x)/postedInvoices");
		assertTrue(properties.invoicesPageIsAddress());
		assertEquals("https://nav.test/api/sat/franchise/v1.0/companies(x)/postedInvoices",
				properties.invoicesPageAddress(null));
	}

	@Test
	@DisplayName("Without company-id: the id looked up once by the company's name on the zsretail API, then the invoices under it")
	void companyLookedUp() {
		properties.setCompanyId(null);
		server.expect(requestTo("http://nav.test:7048/BC280/api/sat/zsretail/v1.0/companies?$filter=name%20eq%20'ESP'"))
				.andRespond(withSuccess("{\"value\":[{\"id\":\"" + COMPANY_ID + "\",\"name\":\"ESP\"}]}",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(PAGE + "?$filter=number%20eq%20'FVE2600001'&$orderby=number&$top=1&" + LINES))
				.andRespond(withSuccess("{\"value\":[" + invoice("FVE2600001") + "]}", MediaType.APPLICATION_JSON));

		assertEquals("FVE2600001", numbers(client.readInvoicesByNumbers(Collections.singletonList("FVE2600001"))));
		assertEquals(PAGE, client.invoicesPageUrl(), "the id kept: no second lookup");
		server.verify();
	}

	@Test
	@DisplayName("A year after a number: the whole address (filter, by number, $top page-size, no header $select, the lines), the next link followed, the lines resolved")
	void readAfter() {
		String next = PAGE + "?$skiptoken=2";
		server.expect(requestTo(PAGE + "?$filter=startswith(number,'FVE26')%20and%20number%20gt%20'FVE2600001'%20and%20"
				+ CUSTOMERS + "&$orderby=number&$top=50&" + LINES)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"value\":[" + invoice("FVE2600002") + "],\"@odata.nextLink\":\"" + next + "\"}",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(next)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"value\":[" + invoice("FVE2600003") + "]}", MediaType.APPLICATION_JSON));

		List<ZenInvoiceRow> rows = client.readInvoicesAfter("FVE26", "FVE2600001", STORES);

		server.verify();
		assertEquals("FVE2600002,FVE2600003", numbers(rows));
		assertEquals("32ZFCHD21", rows.get(0).getLines().get(0).getNo());
		assertEquals("001", rows.get(0).getLines().get(0).getVariantCode());
		assertEquals("Item", rows.get(0).getLines().get(0).getType());
		assertEquals("90.000", rows.get(0).getLines().get(0).getLineAmount().toPlainString(), "exact decimals");
		assertEquals("90.000", rows.get(0).decimal(ZenInvoiceRow.AMOUNT).toPlainString(), "exact decimals");
	}

	@Test
	@DisplayName("At most page-size invoices: no next link followed past it, the extra rows of a page cut")
	void pageSize() {
		properties.getInvoices().setPageSize(2);
		server.expect(requestTo(PAGE + "?$filter=startswith(number,'FVE26')%20and%20" + CUSTOMERS
				+ "&$orderby=number&$top=2&" + LINES))
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
	@DisplayName("The first invoice of the stores: one GET, $top=1, $select=number; none on the page: null")
	void firstInvoice() {
		String filter = "$filter=startswith(number,'FVE')%20and%20" + CUSTOMERS
				+ "&$orderby=number&$top=1&$select=number";
		server.expect(requestTo(PAGE + "?" + filter)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"value\":[{\"number\":\" FVE2500007 \"}]}", MediaType.APPLICATION_JSON));
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
		String first = asked.subList(0, 20).stream().map(number -> "number%20eq%20'" + number + "'")
				.collect(Collectors.joining("%20or%20"));
		server.expect(requestTo(PAGE + "?$filter=" + first + "&$orderby=number&$top=20&" + LINES))
				.andRespond(withSuccess("{\"value\":[" + invoice("FVE2600001") + "]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(PAGE + "?$filter=number%20eq%20'FVE2600021'&$orderby=number&$top=1&" + LINES))
				.andRespond(withSuccess("{\"value\":[" + invoice("FVE2600021") + "]}", MediaType.APPLICATION_JSON));

		assertEquals("FVE2600001,FVE2600021", numbers(client.readInvoicesByNumbers(asked)));
		server.verify();
	}

	@Test
	@DisplayName("An HTTP error names the page and the status")
	void httpError() {
		server.expect(requestTo(PAGE + "?$filter=number%20eq%20'FVE2600001'&$orderby=number&$top=1&" + LINES))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));
		ZenReadException failure = assertThrows(ZenReadException.class,
				() -> client.readInvoicesByNumbers(Collections.singletonList("FVE2600001")));
		assertEquals("salesInvoices", failure.getPage());
		assertTrue(failure.getMessage().startsWith("zen: reading the page salesInvoices failed: HTTP 404"),
				failure.getMessage());
	}
}
