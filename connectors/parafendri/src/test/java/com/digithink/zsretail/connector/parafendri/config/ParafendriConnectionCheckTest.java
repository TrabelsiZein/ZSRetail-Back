package com.digithink.zsretail.connector.parafendri.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.erp.api.ErpStoreLocation;

/**
 * ParaFendri, step 1: the connection check after the start, against a mock server (no live call, no Spring context):
 * one INFO line when the company and the store's location are found, else one WARNING naming the problem (Business
 * Central down, an HTTP status, the company missing, DEFAULT_LOCATION empty or not a location of the company, the
 * location not readable); never an exception, on its own daemon thread.
 */
class ParafendriConnectionCheckTest {

	static final String COMPANY_ID = "6b29fc40-ca47-1067-b31d-00dd010662da";
	static final String COMPANIES = "http://bc.test:7048/BC280/api/sat/zsretail/v1.0/companies";
	static final String LOCATIONS = COMPANIES + "(" + COMPANY_ID + ")/locations?$select=code,name,lastModifiedDateTime";
	static final String PREFIX = "ERP connector parafendri: ";

	private ParafendriProperties properties;
	private MockRestServiceServer server;
	private ParafendriRestClient client;

	@BeforeEach
	void setUp() {
		properties = new ParafendriProperties();
		properties.setBaseUrl("http://bc.test:7048/BC280");
		properties.setCompanyId(COMPANY_ID);
		properties.setUsername("user");
		properties.setPassword("secret");
		RestTemplate template = ParafendriConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		client = new ParafendriRestClient(template, properties);
	}

	/** The store's location as the application answers it (General Setup DEFAULT_LOCATION). */
	static ErpStoreLocation location(Supplier<String> code) {
		return new ErpStoreLocation() {

			@Override
			public String defaultLocationCode() {
				return code.get();
			}

			@Override
			public List<String> defaultResponsibilityCenters() {
				return Collections.emptyList();
			}
		};
	}

	private ParafendriConnectionCheck check(String code) {
		return new ParafendriConnectionCheck(client, location(() -> code));
	}

	private void companies() throws IOException {
		server.expect(requestTo(COMPANIES)).andRespond(withSuccess(sample("companies.json"), MediaType.APPLICATION_JSON));
	}

	private void locations() throws IOException {
		server.expect(requestTo(LOCATIONS)).andRespond(withSuccess(sample("locations.json"), MediaType.APPLICATION_JSON));
	}

	@Test
	@DisplayName("All found: one INFO line, the company and the location")
	void found() throws IOException {
		companies();
		locations();
		ParafendriConnectionCheck.Result result = check(" PV-GREMDA ").check();
		server.verify();
		assertTrue(result.ok);
		assertEquals(PREFIX + "Business Central reachable, company PARAFENDRI, location PV-GREMDA found.", result.line);
	}

	@Test
	@DisplayName("Business Central down (no answer): a WARNING naming it, no exception")
	void down() {
		server.expect(requestTo(COMPANIES)).andRespond(request -> {
			throw new ConnectException("Connection refused: connect");
		});
		ParafendriConnectionCheck.Result result = check("PV-GREMDA").check();
		assertFalse(result.ok);
		assertTrue(result.line.startsWith(PREFIX + "connection check failed, the store starts without the ERP:"
				+ " parafendri: reading the page companies failed: no answer or timeout ("), result.line);
		assertTrue(result.line.contains("Connection refused"), result.line);
	}

	@Test
	@DisplayName("An HTTP error (503): a WARNING with the page and the status")
	void httpError() {
		server.expect(requestTo(COMPANIES)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		ParafendriConnectionCheck.Result result = check("PV-GREMDA").check();
		assertFalse(result.ok);
		assertTrue(result.line.startsWith(PREFIX + "connection check failed, the store starts without the ERP:"
				+ " parafendri: reading the page companies failed: HTTP 503 "), result.line);
	}

	@Test
	@DisplayName("The company missing: a WARNING naming the id and the companies found")
	void companyMissing() throws IOException {
		properties.setCompanyId("00000000-0000-0000-0000-000000000000");
		companies();
		ParafendriConnectionCheck.Result result = check("PV-GREMDA").check();
		assertFalse(result.ok);
		assertTrue(result.line.startsWith(PREFIX + "connection check failed, the store starts without the ERP:"
				+ " parafendri: no company with id 00000000-0000-0000-0000-000000000000 in the API"), result.line);
	}

	@Test
	@DisplayName("DEFAULT_LOCATION empty or absent: a WARNING asking for it, the locations not read")
	void noLocationCode() throws IOException {
		for (String code : new String[] { "", "  ", null }) {
			server.reset();
			companies();
			ParafendriConnectionCheck.Result result = check(code).check();
			server.verify();
			assertFalse(result.ok);
			assertEquals(PREFIX + "Business Central reachable, company PARAFENDRI, but the General Setup"
					+ " DEFAULT_LOCATION is empty: set the store's location code (e.g. PV-GREMDA).", result.line);
		}
	}

	@Test
	@DisplayName("The location not in Business Central: a WARNING naming it")
	void locationMissing() throws IOException {
		companies();
		locations();
		ParafendriConnectionCheck.Result result = check("PV-TUNIS").check();
		assertFalse(result.ok);
		assertEquals(PREFIX + "Business Central reachable, company PARAFENDRI, but the location PV-TUNIS (General Setup"
				+ " DEFAULT_LOCATION) is not a location of the company: check the code.", result.line);
	}

	@Test
	@DisplayName("The locations page failing, or DEFAULT_LOCATION not readable: a WARNING, no exception")
	void otherFailures() throws IOException {
		companies();
		server.expect(requestTo(LOCATIONS)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		ParafendriConnectionCheck.Result result = check("PV-GREMDA").check();
		assertFalse(result.ok);
		assertTrue(result.line.contains("parafendri: reading the page locations failed: HTTP 404 "), result.line);

		server.reset();
		companies();
		result = new ParafendriConnectionCheck(client, location(() -> {
			throw new IllegalStateException("database not reachable");
		})).check();
		assertFalse(result.ok);
		assertEquals(PREFIX + "connection check failed, the store starts without the ERP: database not reachable",
				result.line);
	}

	@Test
	@DisplayName("After the start: on its own daemon thread, which ends without an exception with Business Central down")
	void ownThread() throws InterruptedException {
		server.expect(manyTimes(), requestTo(COMPANIES)).andRespond(request -> {
			throw new ConnectException("Connection refused: connect");
		});
		ParafendriConnectionCheck check = check("PV-GREMDA");
		assertDoesNotThrow(check::checkAndLog);
		Thread thread = check.start();
		assertEquals("parafendri-connection-check", thread.getName());
		assertTrue(thread.isDaemon());
		assertTrue(thread != Thread.currentThread());
		thread.join(10_000);
		assertFalse(thread.isAlive(), "the check ended");
	}

	static String sample(String name) throws IOException {
		try (InputStream in = ParafendriConnectionCheckTest.class.getResourceAsStream("/parafendri/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
