package com.digithink.zsretail.connector.happyness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.conn.ConnectTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.happyness.client.NavPosPagesCatalogueSource;
import com.digithink.zsretail.connector.happyness.client.NavPosPagesReadException;
import com.digithink.zsretail.connector.happyness.client.NavPosPagesRestClient;
import com.digithink.zsretail.connector.happyness.config.NavPosPagesConfig;
import com.digithink.zsretail.connector.happyness.config.NavPosPagesProperties;
import com.digithink.zsretail.core.erp.engine.RetryingCatalogueSource;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpRead;

/**
 * 3.1.0 (follow-up of 546ecbc): Happyness's page reads as the catalogue engine makes them, the connector's real REST
 * client and source wrapped in the engine's RetryingCatalogueSource. A connect timeout is wrapped by RestTemplate
 * (ResourceAccessException) then by the client (NavPosPagesReadException) with the I/O error kept as the cause, so the
 * engine reads the page again once and succeeds; an HTTP error is not retried.
 */
class NavPosPagesReadRetryTest {

	private static final String BARCODES = NavPosPagesTestSupport.COMPANY_URL + "ItemBarCodePOS?$filter=Entry_No%20gt%200"
			+ "&$orderby=Entry_No&$top=250&$select=Item_No,Cross_Reference_No,Entry_No";

	private MockRestServiceServer server;
	private final List<Duration> pauses = new ArrayList<>();
	private RetryingCatalogueSource engineReads;

	@BeforeEach
	void setUp() {
		NavPosPagesProperties properties = NavPosPagesTestSupport.properties();
		properties.setBarcodePageSize(250);
		RestTemplate template = NavPosPagesConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		NavPosPagesCatalogueSource source = new NavPosPagesCatalogueSource(
				new NavPosPagesRestClient(template, properties), properties);
		engineReads = new RetryingCatalogueSource(source, pauses::add);
	}

	private static ResponseCreator connectTimeout() {
		return request -> {
			throw new ConnectTimeoutException("Connect to bc.test:7048 failed: Connect timed out");
		};
	}

	@Test
	@DisplayName("The barcodes page: a wrapped connect timeout, then the page: read again once after 5 s, the rows given")
	void connectTimeoutRetried() {
		server.expect(requestTo(BARCODES)).andExpect(method(HttpMethod.GET)).andRespond(connectTimeout());
		server.expect(requestTo(BARCODES)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
				"{\"value\":[{\"Item_No\":\"000001\",\"Cross_Reference_No\":\"6191234567890\",\"Entry_No\":7}]}",
				MediaType.APPLICATION_JSON));

		ErpRead<ErpBarcodeRow> read = engineReads.barcodesAfter(0);

		server.verify();
		assertEquals(1, read.getKept());
		assertEquals("6191234567890", read.getRows().get(0).getBarcode().getBarcode());
		assertEquals(1, pauses.size());
		assertEquals(Duration.ofSeconds(5), pauses.get(0));
	}

	@Test
	@DisplayName("The client keeps the I/O error as the cause of its exception (what the retry looks for)")
	void causeKept() {
		server.expect(requestTo(BARCODES)).andRespond(connectTimeout());
		server.expect(requestTo(BARCODES)).andRespond(connectTimeout());

		NavPosPagesReadException failure = assertThrows(NavPosPagesReadException.class,
				() -> engineReads.barcodesAfter(0));
		Throwable cause = failure;
		while (cause.getCause() != null && !(cause instanceof IOException)) {
			cause = cause.getCause();
		}
		assertTrue(cause instanceof ConnectTimeoutException, String.valueOf(cause));
		assertEquals(1, pauses.size(), "two failures: one retry, then the step fails");
	}

	@Test
	@DisplayName("An HTTP error (401) is an answer, not an I/O error: not retried")
	void httpErrorNotRetried() {
		server.expect(requestTo(BARCODES)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThrows(NavPosPagesReadException.class, () -> engineReads.barcodesAfter(0));
		server.verify();
		assertTrue(pauses.isEmpty());
	}
}
