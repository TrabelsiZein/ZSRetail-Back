package com.digithink.zsretail.connector.parafendri.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCompanyRow;

/**
 * ParaFendri, step 0: the connection test against a mock server (no live call): the companies of the API read from the
 * sample companies.json (two companies, one of them the configured id), the next links followed, company-id found or
 * refused naming the companies found, an HTTP error naming the page and the status, GET only, the NTLM login for the
 * host of base-url only.
 */
class ParafendriRestClientTest {

	static final String COMPANY_ID = "6b29fc40-ca47-1067-b31d-00dd010662da";
	static final String COMPANIES = "http://bc.test:7048/BC280/api/sat/zsretail/v1.0/companies";

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
	@DisplayName("GET only: any other method is refused before it leaves")
	void getOnly() {
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> ParafendriConfig.restTemplate(properties).postForObject(COMPANIES, "{}", String.class));
		assertEquals("The parafendri connector only reads from the ERP (GET): POST " + COMPANIES
				+ " refused, nothing was sent.", refused.getMessage());
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

	static String sample(String name) throws IOException {
		try (InputStream in = ParafendriRestClientTest.class.getResourceAsStream("/parafendri/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
