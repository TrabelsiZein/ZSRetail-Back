package com.digithink.zsretail.connector.parafendri.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.http.auth.AuthScope;
import org.apache.http.client.CredentialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.parafendri.config.ParafendriConfig;
import com.digithink.zsretail.connector.parafendri.config.ParafendriProperties;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCollection;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCompanyRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriLocationRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriStockPointRow;

/**
 * ParaFendri, step 0: the connection test against a mock server (no live call): the companies of the API read from the
 * sample companies.json (two companies, one of them the configured id), the next links followed, company-id found or
 * refused naming the companies found, an HTTP error naming the page and the status, GET only, the NTLM login for the
 * host of base-url only.
 */
class ParafendriRestClientTest {

	static final String COMPANY_ID = "6b29fc40-ca47-1067-b31d-00dd010662da";
	static final String COMPANIES = "http://bc.test:7048/BC280/api/sat/zsretail/v1.0/companies";
	static final String LOCATIONS = COMPANIES + "(" + COMPANY_ID + ")/locations?$select=code,name,lastModifiedDateTime";

	private ParafendriProperties properties;
	private RestTemplate template;
	private MockRestServiceServer server;
	private ParafendriRestClient client;

	@BeforeEach
	void setUp() {
		properties = new ParafendriProperties();
		properties.setBaseUrl("http://bc.test:7048/BC280");
		properties.setCompanyId(COMPANY_ID);
		properties.setDomain("PF");
		properties.setUsername("user");
		properties.setPassword("secret");
		template = ParafendriConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		client = new ParafendriRestClient(template, properties);
	}

	@Test
	@DisplayName("The companies of the API: GET base-url/api-path/companies, every row read")
	void readCompanies() throws IOException {
		server.expect(requestTo(COMPANIES)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(sample("companies.json"), MediaType.APPLICATION_JSON));
		List<ParafendriCompanyRow> rows = client.readCompanies();
		server.verify();
		assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301 CRONUS, " + COMPANY_ID + " PARAFENDRI",
				rows.stream().map(row -> row.getId() + " " + row.getName()).collect(Collectors.joining(", ")));
		assertEquals("ParaFendri", rows.get(1).getDisplayName());
	}

	@Test
	@DisplayName("The connection test: the row of company-id, its case ignored; a base-url ending with / the same")
	void checkCompany() throws IOException {
		properties.setBaseUrl("http://bc.test:7048/BC280/");
		properties.setCompanyId(" " + COMPANY_ID.toUpperCase() + " ");
		server.expect(requestTo(COMPANIES)).andRespond(withSuccess(sample("companies.json"), MediaType.APPLICATION_JSON));
		ParafendriCompanyRow row = client.checkCompany();
		server.verify();
		assertEquals("PARAFENDRI", row.getName());
		assertEquals("http://bc.test:7048/BC280/api/sat/zsretail/v1.0/companies(" + COMPANY_ID.toUpperCase() + ")/",
				properties.companyAddress());
	}

	@Test
	@DisplayName("The connection test, an id not in the API: refused naming it and the companies found")
	void unknownCompany() throws IOException {
		properties.setCompanyId("00000000-0000-0000-0000-000000000000");
		server.expect(requestTo(COMPANIES)).andRespond(withSuccess(sample("companies.json"), MediaType.APPLICATION_JSON));
		ParafendriReadException refused = assertThrows(ParafendriReadException.class, client::checkCompany);
		assertEquals("parafendri: no company with id 00000000-0000-0000-0000-000000000000 in the API (" + COMPANIES
				+ "), companies found: 3f2504e0-4f89-11d3-9a0c-0305e82c3301 (CRONUS), " + COMPANY_ID
				+ " (PARAFENDRI): check erp.parafendri.company-id", refused.getMessage());
		assertEquals("companies", refused.getPage());
		assertNull(refused.getStatus());
	}

	@Test
	@DisplayName("The next links followed to the end")
	void nextLinks() {
		server.expect(requestTo(COMPANIES)).andRespond(withSuccess("{\"value\":[{\"id\":\"a\",\"name\":\"A\"}],"
				+ "\"@odata.nextLink\":\"" + COMPANIES + "?$skiptoken=1\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(COMPANIES + "?$skiptoken=1"))
				.andRespond(withSuccess("{\"value\":[{\"id\":\"b\",\"name\":\"B\"}]}", MediaType.APPLICATION_JSON));
		assertEquals("a, b", client.readCompanies().stream().map(ParafendriCompanyRow::getId)
				.collect(Collectors.joining(", ")));
		server.verify();
	}

	@Test
	@DisplayName("An HTTP error: refused naming the page and the status")
	void httpError() {
		server.expect(requestTo(COMPANIES)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
		ParafendriReadException refused = assertThrows(ParafendriReadException.class, client::checkCompany);
		assertEquals("companies", refused.getPage());
		assertEquals(Integer.valueOf(401), refused.getStatus());
		assertTrue(refused.getMessage().startsWith("parafendri: reading the page companies failed: HTTP 401 "),
				refused.getMessage());
		assertTrue(refused.getMessage().endsWith("(" + COMPANIES + ")"), refused.getMessage());
	}

	@Test
	@DisplayName("Step 1, the locations: GET companies(id)/locations with $select, the six points de vente read")
	void readLocations() throws IOException {
		server.expect(requestTo(LOCATIONS)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(sample("locations.json"), MediaType.APPLICATION_JSON));
		List<ParafendriLocationRow> rows = client.readLocations();
		server.verify();
		assertEquals("PV-GREMDA, PV-LAFRANE, PV-MALL-SF, PV-NABEUL, PV-SOUSSE, PV-TANIOUR",
				rows.stream().map(ParafendriLocationRow::getCode).collect(Collectors.joining(", ")));
		assertEquals("PV Gremda", rows.get(0).getName());
		assertEquals("2026-10-20T09:12:31.207Z", rows.get(0).getLastModifiedDateTime());
	}

	@Test
	@DisplayName("Step 1, checkLocation: found (trimmed, case ignored), not found, blank; next links followed")
	void checkLocation() throws IOException {
		for (String code : new String[] { "PV-GREMDA", " pv-taniour " }) {
			server.reset();
			server.expect(requestTo(LOCATIONS))
					.andRespond(withSuccess(sample("locations.json"), MediaType.APPLICATION_JSON));
			assertTrue(client.checkLocation(code), code);
			server.verify();
		}
		server.reset();
		server.expect(requestTo(LOCATIONS)).andRespond(withSuccess(sample("locations.json"), MediaType.APPLICATION_JSON));
		assertFalse(client.checkLocation("PV-TUNIS"));
		server.verify();
		// A blank code is no location, even beside a row without a code
		ParafendriLocationRow noCode = new ParafendriLocationRow();
		assertFalse(ParafendriRestClient.hasLocation(Arrays.asList(noCode), " "));
		assertFalse(ParafendriRestClient.hasLocation(Arrays.asList(noCode), null));
		server.reset();
		server.expect(requestTo(LOCATIONS)).andRespond(withSuccess("{\"value\":[{\"code\":\"PV-NABEUL\"}],"
				+ "\"@odata.nextLink\":\"" + LOCATIONS + "&$skiptoken=1\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(LOCATIONS + "&$skiptoken=1"))
				.andRespond(withSuccess("{\"value\":[{\"code\":\"PV-SOUSSE\"}]}", MediaType.APPLICATION_JSON));
		assertTrue(client.checkLocation("PV-SOUSSE"), "on the second page");
		server.verify();
	}

	@Test
	@DisplayName("Step 1, the locations: an HTTP error names the page locations and the status")
	void locationsError() {
		server.expect(requestTo(LOCATIONS)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		ParafendriReadException refused = assertThrows(ParafendriReadException.class, () -> client.checkLocation("PV-GREMDA"));
		assertEquals("locations", refused.getPage());
		assertEquals(Integer.valueOf(404), refused.getStatus());
		assertTrue(refused.getMessage().startsWith("parafendri: reading the page locations failed: HTTP 404 "),
				refused.getMessage());
	}

	@Test
	@DisplayName("GET only, but a POST to salesOrders (step 6): any other method or address refused before it leaves")
	void getOnly() {
		RestTemplate guarded = ParafendriConfig.restTemplate(properties);
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> guarded.postForObject(COMPANIES, "{}", String.class));
		assertEquals("The parafendri connector only reads from the ERP (GET), apart from posting the tickets to"
				+ " salesOrders: POST " + COMPANIES + " refused, nothing was sent.", refused.getMessage());
		String salesOrders = COMPANIES + "(" + COMPANY_ID + ")/salesOrders";
		assertThrows(IllegalStateException.class, () -> guarded.put(salesOrders, "{}"), "a PUT, even to salesOrders");
		assertThrows(IllegalStateException.class,
				() -> guarded.postForObject(salesOrders + "?$expand=salesOrderLines", "{}", String.class), "a query");
		assertThrows(IllegalStateException.class,
				() -> guarded.postForObject(COMPANIES + "(" + COMPANY_ID + ")/salesOrdersX", "{}", String.class));
		// The one write: a POST to .../salesOrders passes (answered by the mock server)
		MockRestServiceServer posts = MockRestServiceServer.bindTo(guarded).build();
		posts.expect(requestTo(salesOrders)).andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		guarded.postForObject(salesOrders, "{}", String.class);
		posts.verify();
	}

	@Test
	@DisplayName("Step 6: Business Central's error text, its code and message; a body that is not JSON as it is")
	void errorText() {
		assertEquals("BadRequest: Invalid field", ParafendriRestClient.errorText(
				"{\"error\":{\"code\":\"BadRequest\",\"message\":\"Invalid field\"}}"));
		assertEquals("Invalid field", ParafendriRestClient.errorText("{\"error\":{\"message\":\"Invalid field\"}}"));
		assertEquals("Service Unavailable", ParafendriRestClient.errorText(" Service Unavailable "));
		assertEquals("", ParafendriRestClient.errorText(null));
	}

	@Test
	@DisplayName("The NTLM login: for the host and port of base-url only, never for another host")
	void credentialsForBaseUrlOnly() {
		CredentialsProvider credentials = ParafendriConfig.credentials(properties);
		assertNotNull(credentials.getCredentials(new AuthScope("bc.test", 7048)));
		assertNotNull(credentials.getCredentials(new AuthScope("BC.TEST", 7048)));
		assertNull(credentials.getCredentials(new AuthScope("other.test", 7048)));
		assertNull(credentials.getCredentials(new AuthScope("bc.test", 7049)));
		// No port in base-url: any port of that host, still no other host
		properties.setBaseUrl("https://bc.test/BC280");
		credentials = ParafendriConfig.credentials(properties);
		assertNotNull(credentials.getCredentials(new AuthScope("bc.test", 443)));
		assertNull(credentials.getCredentials(new AuthScope("other.test", 443)));
	}

	@Test
	@DisplayName("Step 3, the filter of stockPoints: the location (a quote doubled), from a checkpoint ge its time in UTC")
	void stockPointsFilter() {
		assertEquals("locationCode eq 'PV-GREMDA'", ParafendriRestClient.stockPointsFilter(" PV-GREMDA ", null));
		assertEquals("locationCode eq 'O''NEIL'", ParafendriRestClient.stockPointsFilter("O'NEIL", null));
		assertEquals("locationCode eq 'PV-GREMDA' and lastModifiedDateTime ge 2026-10-25T08:15:42.317Z",
				ParafendriRestClient.stockPointsFilter("PV-GREMDA",
						java.time.OffsetDateTime.parse("2026-10-25T09:15:42.317+01:00")));
	}

	@Test
	@DisplayName("Step 3, stockPoints read to the end through the next links; the first page alone with its next link")
	void stockPointsPaging() {
		String first = COMPANIES + "(" + COMPANY_ID + ")/stockPoints?$filter=locationCode%20eq%20'PV-GREMDA'";
		String next = first + "&$skiptoken=ITM001";
		String page1 = "{\"value\":[{\"locationCode\":\"PV-GREMDA\",\"itemNo\":\"ITM001\",\"unitPrice\":18.90756}],"
				+ "\"@odata.nextLink\":\"" + next + "\"}";
		server.expect(requestTo(first)).andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));
		server.expect(requestTo(next)).andRespond(withSuccess(
				"{\"value\":[{\"locationCode\":\"PV-GREMDA\",\"itemNo\":\"ITM002\"}]}", MediaType.APPLICATION_JSON));
		assertEquals("ITM001, ITM002", client.readStockPoints("PV-GREMDA", null).stream()
				.map(ParafendriStockPointRow::getItemNo).collect(Collectors.joining(", ")));
		server.verify();

		server.reset();
		server.expect(requestTo(first)).andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));
		ParafendriCollection<ParafendriStockPointRow> page = client.readStockPointsFirstPage("PV-GREMDA", null);
		assertEquals(1, page.getValue().size());
		assertEquals(next, page.getNextLink());
		assertEquals(new java.math.BigDecimal("18.90756"),
				page.getValue().get(0)
						.getUnitPrice(), "read as a BigDecimal, every decimal kept");
		server.verify();
	}

	@Test
	@DisplayName("Step 4, barCodes: no filter without a checkpoint, ge its time in UTC with one")
	void barcodesUri() {
		String barcodes = COMPANIES + "(" + COMPANY_ID + ")/barCodes";
		assertEquals(barcodes, client.barcodesUri(null).toString());
		assertEquals(barcodes + "?$filter=lastModifiedDateTime%20ge%202026-10-25T08:00:00.117Z",
				client.barcodesUri(java.time.OffsetDateTime.parse("2026-10-25T09:00:00.117+01:00")).toString());
	}

	@Test
	@DisplayName("Step 4, the barcodes of some items: itemNo eq .. or .., 20 items per GET (25 items: two GETs), a quote doubled")
	void barcodesOfItems() {
		assertEquals("itemNo eq 'A' or itemNo eq 'O''NEIL'",
				ParafendriRestClient.itemsFilter(Arrays.asList("A", "O'NEIL")));
		List<String> items = new java.util.ArrayList<>();
		for (int i = 1; i <= 25; i++) {
			items.add(String.format("ITM%03d", i));
		}
		String barcodes = COMPANIES + "(" + COMPANY_ID + ")/barCodes?$filter=";
		server.expect(requestTo(barcodes + ParafendriRestClient.itemsFilter(items.subList(0, 20)).replace(" ", "%20")))
				.andRespond(withSuccess("{\"value\":[{\"itemNo\":\"ITM001\",\"barCode\":\"1\"}]}",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(barcodes + ParafendriRestClient.itemsFilter(items.subList(20, 25)).replace(" ", "%20")))
				.andRespond(withSuccess("{\"value\":[{\"itemNo\":\"ITM021\",\"barCode\":\"21\"}]}",
						MediaType.APPLICATION_JSON));
		assertEquals(2, client.readBarcodesOfItems(items).size());
		server.verify();
		assertEquals(0, client.readBarcodesOfItems(java.util.Collections.emptyList()).size());
	}

	static String sample(String name) throws IOException {
		try (InputStream in = ParafendriRestClientTest.class.getResourceAsStream("/parafendri/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
