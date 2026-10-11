package com.digithink.zsretail.holink.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.Arrays;

import org.apache.http.client.config.RequestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.headoffice.dto.ReturnCopyDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.headoffice.dto.SessionCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketCopyDTO;
import com.digithink.zsretail.holink.dto.HeadOfficeCallResult;
import com.digithink.zsretail.holink.dto.SalesPushAnswer;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.service.GeneralSetupService;

/**
 * Head office plan, task 1.4: the store's HeadOfficeClient. Every line of the result mapping, both headers on every
 * call, the store code read from DEFAULT_LOCATION at each call, a trailing slash in headoffice.url tolerated, and
 * never an exception. The transport is Spring's MockRestServiceServer on a plain RestTemplate (no Spring context), so
 * RestTemplate's own error handling is exercised. An unexpected request fails the test. Task 2.4: the push of a batch
 * of sales copies, delivered or not, on the same mapping.
 */
class HeadOfficeClientTest {

	private static final String URL = "http://ho.example:888/zsretail/api/";
	private static final String HEARTBEAT = "http://ho.example:888/zsretail/api/ho/heartbeat";
	private static final String KEY = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
	private static final String SERVER_TIME = "2026-10-03T10:15:04.123+01:00";

	/** DEFAULT_LOCATION in the general setup; a RuntimeException value is thrown instead. */
	private Object location = "RS01";
	private int locationReads;
	private RestTemplate restTemplate;
	private MockRestServiceServer server;
	private HeadOfficeClient client;

	@BeforeEach
	void setUp() {
		GeneralSetupService generalSetup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				assertEquals("DEFAULT_LOCATION", code);
				locationReads++;
				if (location instanceof RuntimeException) {
					throw (RuntimeException) location;
				}
				return (String) location;
			}
		};
		restTemplate = new RestTemplate();
		server = MockRestServiceServer.bindTo(restTemplate).build();
		client = new HeadOfficeClient(restTemplate, generalSetup, URL, "  " + KEY + " ", "1.12.0");
	}

	/** A heartbeat expected with these headers and body. */
	private ResponseActions expectHeartbeat(String storeCode) {
		return server.expect(requestTo(HEARTBEAT))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("X-Store-Code", storeCode))
				.andExpect(header("X-Store-Key", KEY))
				.andExpect(content().json("{\"appVersion\":\"1.12.0\"}"));
	}

	/** One heartbeat against this answer; the request is checked. */
	private HeadOfficeCallResult answer(ResponseCreator response) {
		server.reset();
		expectHeartbeat("RS01").andRespond(response);
		HeadOfficeCallResult result = client.heartbeat();
		server.verify();
		return result;
	}

	private static ResponseCreator failWith(IOException e) {
		return request -> {
			throw e;
		};
	}

	@Test
	@DisplayName("200: ONLINE with the head office time; POST /ho/heartbeat with X-Store-Code, X-Store-Key (trimmed) and the version")
	void online() {
		HeadOfficeCallResult result = answer(withSuccess(
				"{\"storeCode\":\"RS01\",\"serverTime\":\"" + SERVER_TIME + "\"}", MediaType.APPLICATION_JSON));
		assertEquals(HeadOfficeLinkState.ONLINE, result.getState());
		assertEquals(SERVER_TIME, result.getServerTime());
		assertNull(result.getMessage());
	}

	@Test
	@DisplayName("401: REFUSED, store code or key refused")
	void unauthorized() {
		HeadOfficeCallResult result = answer(withStatus(HttpStatus.UNAUTHORIZED)
				.body("{\"code\":401,\"msg\":\"Invalid store credentials.\"}").contentType(MediaType.APPLICATION_JSON));
		assertEquals(HeadOfficeLinkState.REFUSED, result.getState());
		assertEquals("store code or key refused by the head office", result.getMessage());
	}

	@Test
	@DisplayName("402: REFUSED, the head office has no valid license")
	void paymentRequired() {
		HeadOfficeCallResult result = answer(withStatus(HttpStatus.PAYMENT_REQUIRED)
				.body("{\"licenseError\":true,\"status\":\"EXPIRED\"}").contentType(MediaType.APPLICATION_JSON));
		assertEquals(HeadOfficeLinkState.REFUSED, result.getState());
		assertEquals("the head office has no valid license", result.getMessage());
	}

	@Test
	@DisplayName("Any other status (4xx, 5xx, and 2xx or 3xx other than 200): ERROR with the status code")
	void otherStatus() {
		for (HttpStatus status : new HttpStatus[] { HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND,
				HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.NO_CONTENT, HttpStatus.FOUND }) {
			HeadOfficeCallResult result = answer(withStatus(status));
			assertEquals(HeadOfficeLinkState.ERROR, result.getState(), status.toString());
			assertEquals("unexpected answer from the head office: HTTP " + status.value(), result.getMessage());
			assertNull(result.getServerTime());
		}
	}

	@Test
	@DisplayName("200 whose body cannot be read (HTML, broken JSON, empty): ERROR, unreadable answer")
	void unreadableBody() {
		HeadOfficeCallResult html = answer(withSuccess("<html>Login</html>", MediaType.TEXT_HTML));
		HeadOfficeCallResult broken = answer(withSuccess("{\"storeCode\":", MediaType.APPLICATION_JSON));
		HeadOfficeCallResult empty = answer(withSuccess());
		for (HeadOfficeCallResult result : new HeadOfficeCallResult[] { html, broken, empty }) {
			assertEquals(HeadOfficeLinkState.ERROR, result.getState());
			assertTrue(result.getMessage().startsWith("unreadable answer from the head office"), result.getMessage());
		}
	}

	@Test
	@DisplayName("Connection refused, unknown host, timeout: OFFLINE with the cause")
	void offline() {
		HeadOfficeCallResult refused = answer(failWith(new ConnectException("Connection refused")));
		assertEquals(HeadOfficeLinkState.OFFLINE, refused.getState());
		assertEquals("head office unreachable (ConnectException: Connection refused)", refused.getMessage());

		assertEquals(HeadOfficeLinkState.OFFLINE, answer(failWith(new UnknownHostException("ho.example"))).getState());

		HeadOfficeCallResult timeout = answer(failWith(new SocketTimeoutException("Read timed out")));
		assertEquals(HeadOfficeLinkState.OFFLINE, timeout.getState());
		assertTrue(timeout.getMessage().contains("Read timed out"), timeout.getMessage());
	}

	@Test
	@DisplayName("DEFAULT_LOCATION null, empty or blank: NOT_CONFIGURED and no call is made")
	void notConfigured() {
		for (String value : new String[] { null, "", "   " }) {
			location = value;
			HeadOfficeCallResult result = client.heartbeat();
			assertEquals(HeadOfficeLinkState.NOT_CONFIGURED, result.getState());
			assertTrue(result.getMessage().contains("DEFAULT_LOCATION"), result.getMessage());
		}
		server.verify(); // no request expected: any call would have failed
	}

	@Test
	@DisplayName("DEFAULT_LOCATION that cannot be read (database down): ERROR, no call, no exception")
	void storeCodeUnreadable() {
		location = new DataAccessResourceFailureException("database down");
		HeadOfficeCallResult result = client.heartbeat();
		assertEquals(HeadOfficeLinkState.ERROR, result.getState());
		assertTrue(result.getMessage().startsWith("DEFAULT_LOCATION could not be read"), result.getMessage());
		server.verify();
	}

	@Test
	@DisplayName("The store code is read at each call (not cached) and trimmed")
	void storeCodeReadAtEachCall() {
		String ok = "{\"storeCode\":\"X\",\"serverTime\":\"" + SERVER_TIME + "\"}";
		location = " rs02 ";
		expectHeartbeat("rs02").andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));
		assertEquals(HeadOfficeLinkState.ONLINE, client.heartbeat().getState());

		location = "RS03";
		server.reset();
		expectHeartbeat("RS03").andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));
		assertEquals(HeadOfficeLinkState.ONLINE, client.heartbeat().getState());
		server.verify();
		assertEquals(2, locationReads);
	}

	@Test
	@DisplayName("Trailing slashes in headoffice.url are tolerated")
	void trailingSlash() {
		assertEquals("http://ho.example:888/zsretail/api", client.getBaseUrl());
		GeneralSetupService generalSetup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "RS01";
			}
		};
		HeadOfficeClient twoSlashes = new HeadOfficeClient(restTemplate, generalSetup, " " + URL + "/ ", KEY, "1.12.0");
		assertEquals("http://ho.example:888/zsretail/api", twoSlashes.getBaseUrl());
		server.reset();
		expectHeartbeat("RS01").andRespond(withStatus(HttpStatus.UNAUTHORIZED));
		assertEquals(HeadOfficeLinkState.REFUSED, twoSlashes.heartbeat().getState());
		server.verify();
	}

	@Test
	@DisplayName("Own RestTemplate (Apache HttpClient) with connect 5 s and read 10 s")
	void timeouts() {
		Object factory = HeadOfficeClient.newRestTemplate().getRequestFactory();
		assertTrue(factory instanceof HttpComponentsClientHttpRequestFactory, factory.getClass().getName());
		RequestConfig config = (RequestConfig) ReflectionTestUtils.getField(factory, "requestConfig");
		assertEquals(5_000, config.getConnectTimeout());
		assertEquals(10_000, config.getSocketTimeout());
		assertEquals(5_000, config.getConnectionRequestTimeout());
	}

	// --- Task 2.4: sales copies ---

	private static final String SALES = "http://ho.example:888/zsretail/api/ho/sales/";

	private static TicketCopyDTO ticketCopy(String number) {
		TicketCopyDTO copy = new TicketCopyDTO();
		copy.setSalesNumber(number);
		copy.setSalesDate(LocalDateTime.of(2026, 10, 3, 10, 15, 30));
		return copy;
	}

	/** One push of a ticket against this answer; the request is checked. */
	private SalesPushAnswer push(ResponseCreator response) {
		server.reset();
		server.expect(requestTo(SALES + "tickets")).andRespond(response);
		SalesPushAnswer answer = client.push(SalesCopyType.TICKET, Arrays.asList(ticketCopy("T-1")));
		server.verify();
		return answer;
	}

	@Test
	@DisplayName("Task 2.4: push POSTs the batch as a JSON array to /ho/sales/<type> with both headers; 200 gives one result per document")
	void pushDelivered() {
		server.expect(requestTo(SALES + "tickets"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("X-Store-Code", "RS01"))
				.andExpect(header("X-Store-Key", KEY))
				.andExpect(content().json("[{\"salesNumber\":\"T-1\",\"salesDate\":\"2026-10-03T10:15:30\"},"
						+ "{\"salesNumber\":\"T-2\"}]"))
				.andRespond(withSuccess("{\"results\":[{\"documentNumber\":\"T-1\",\"accepted\":true,\"message\":null},"
						+ "{\"documentNumber\":\"T-2\",\"accepted\":false,\"message\":\"salesDate is required\"}]}",
						MediaType.APPLICATION_JSON));
		TicketCopyDTO second = new TicketCopyDTO();
		second.setSalesNumber("T-2");

		SalesPushAnswer answer = client.push(SalesCopyType.TICKET, Arrays.asList(ticketCopy("T-1"), second));

		server.verify();
		assertTrue(answer.isDelivered());
		assertEquals(HeadOfficeLinkState.ONLINE, answer.getState());
		assertNull(answer.getMessage());
		assertEquals(Arrays.asList(SalesCopyResultDTO.accepted("T-1"),
				SalesCopyResultDTO.rejected("T-2", "salesDate is required")), answer.getResults());

		for (SalesCopyType type : new SalesCopyType[] { SalesCopyType.RETURN, SalesCopyType.SESSION }) {
			server.reset();
			server.expect(requestTo(SALES + (type == SalesCopyType.RETURN ? "returns" : "sessions")))
					.andExpect(method(HttpMethod.POST))
					.andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
			assertTrue(client.push(type, Arrays.asList(type == SalesCopyType.RETURN ? new ReturnCopyDTO()
					: new SessionCopyDTO())).isDelivered(), type.name());
			server.verify();
		}
	}

	@Test
	@DisplayName("Task 2.4: a push that is not delivered follows the heartbeat table and has no result")
	void pushNotDelivered() {
		SalesPushAnswer refused = push(withStatus(HttpStatus.UNAUTHORIZED));
		assertEquals(HeadOfficeLinkState.REFUSED, refused.getState());
		assertEquals("store code or key refused by the head office", refused.getMessage());
		assertTrue(refused.getResults().isEmpty());

		assertEquals("the head office has no valid license", push(withStatus(HttpStatus.PAYMENT_REQUIRED)).getMessage());
		SalesPushAnswer error = push(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		assertEquals(HeadOfficeLinkState.ERROR, error.getState());
		assertEquals("unexpected answer from the head office: HTTP 500", error.getMessage());

		SalesPushAnswer offline = push(failWith(new ConnectException("Connection refused")));
		assertEquals(HeadOfficeLinkState.OFFLINE, offline.getState());
		assertEquals("head office unreachable (ConnectException: Connection refused)", offline.getMessage());
		assertFalse(offline.isDelivered());

		assertEquals(HeadOfficeLinkState.ERROR,
				push(withSuccess("<html>proxy</html>", MediaType.TEXT_HTML)).getState());
		SalesPushAnswer noResults = push(withSuccess("{\"results\":null}", MediaType.APPLICATION_JSON));
		assertEquals(HeadOfficeLinkState.ERROR, noResults.getState());
		assertEquals("unreadable answer from the head office (no results)", noResults.getMessage());

		location = " ";
		server.reset();
		SalesPushAnswer notConfigured = client.push(SalesCopyType.TICKET, Arrays.asList(ticketCopy("T-1")));
		server.verify(); // no request
		assertEquals(HeadOfficeLinkState.NOT_CONFIGURED, notConfigured.getState());
		assertFalse(notConfigured.isDelivered());
	}
}
