package com.digithink.zsretail.connector.parafendri.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.connector.parafendri.config.ParafendriConfig;
import com.digithink.zsretail.connector.parafendri.config.ParafendriProperties;
import com.digithink.zsretail.erp.api.dto.ErpInvoiceResult;

/**
 * ParaFendri, step 7: requestInvoice against a mock server (no live POST). The sales order is looked up first in
 * shipAndInvoice by orderNo: invoiced already, its invoice answers (never made twice); else one POST { orderNo }
 * (shipAndInvoice-request.json), Business Central's invoice number and PDF (base64, decoded) in the result. The fiscal
 * registration and name cannot be sent: said in the message, one warning. A refusal answers Business Central's own error
 * text; a failed lookup posts nothing.
 */
class ParafendriInvoiceTest {

	static final String SHIP_AND_INVOICE = ParafendriTicketExportTest.COMPANY + "shipAndInvoice";
	static final String LOOKUP = SHIP_AND_INVOICE + "?$filter=orderNo%20eq%20'CV-000001'";
	static final String NONE = "{\"value\":[]}";
	/** The PDF of the sample answer (49 bytes). */
	static final String PDF = "%PDF-1.4\n%ZS Retail test invoice FV-000001\n%%EOF\n";

	private MockRestServiceServer server;
	private ParafendriConnector connector;

	@BeforeEach
	void setUp() {
		ParafendriProperties properties = new ParafendriProperties();
		properties.setBaseUrl("http://bc.test:7048/BC280");
		properties.setCompanyId(ParafendriConnectorTest.COMPANY_ID);
		properties.setUsername("user");
		properties.setPassword("secret");
		RestTemplate template = ParafendriConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		connector = new ParafendriConnector(new ParafendriRestClient(template, properties),
				ParafendriConnectorTest.location(() -> "PV-GREMDA"));
	}

	private static String sample(String name) throws IOException {
		try (InputStream in = ParafendriInvoiceTest.class.getResourceAsStream("/parafendri/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("Not invoiced yet: looked up (none), one POST { orderNo }; the invoice number, the PDF decoded, the fiscal warning")
	void posts() throws IOException {
		server.expect(requestTo(LOOKUP)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(SHIP_AND_INVOICE)).andExpect(method(HttpMethod.POST))
				.andExpect(content().json(sample("shipAndInvoice-request.json"), true))
				.andRespond(withSuccess(sample("shipAndInvoice-answer.json"), MediaType.APPLICATION_JSON));
		ErpInvoiceResult result = connector.requestInvoice("CV-000001", "1234567A/M/000", "Société Test");
		server.verify();
		assertTrue(result.isSuccess(), result.getMessage());
		assertEquals("FV-000001", result.getInvoiceNo());
		assertEquals("FV-000001", result.getExternalReference());
		assertEquals(PDF, new String(result.getPdf(), StandardCharsets.UTF_8));
		assertEquals("parafendri: sales order CV-000001 shipped and invoiced as FV-000001; not sent (shipAndInvoice has"
				+ " no field for them): fiscal registration 1234567A/M/000, invoice name Société Test", result.getMessage());
		assertEquals(Collections.singletonList("shipAndInvoice has no fiscal fields — ask the BC contact"),
				result.getWarnings());
		assertEquals(SHIP_AND_INVOICE, result.getUrl());
		assertEquals(Collections.singletonMap("orderNo", "CV-000001"), result.getActualRequestPayload());
		Map<?, ?> logged = (Map<?, ?>) result.getActualResponsePayload();
		assertEquals("FV-000001", logged.get("invoiceNo"));
		assertEquals("68 characters, not logged", logged.get("invoicePdfBase64"), "the PDF not in the log");
	}

	@Test
	@DisplayName("Invoiced already (an earlier run whose answer was lost): its invoice answers, nothing posted")
	void alreadyInvoiced() throws IOException {
		server.expect(requestTo(LOOKUP))
				.andRespond(withSuccess("{\"value\":[" + sample("shipAndInvoice-answer.json") + "]}",
						MediaType.APPLICATION_JSON));
		ErpInvoiceResult result = connector.requestInvoice("CV-000001", "1234567A/M/000", null);
		server.verify();
		assertTrue(result.isSuccess());
		assertEquals("FV-000001", result.getInvoiceNo());
		assertEquals(PDF, new String(result.getPdf(), StandardCharsets.UTF_8));
		assertEquals("parafendri: sales order CV-000001 is already invoiced in Business Central as FV-000001: not asked"
				+ " again", result.getMessage());
		assertTrue(result.getWarnings().isEmpty(), "nothing sent, nothing to warn");
	}

	@Test
	@DisplayName("Business Central refuses the POST: a failure with its status and its own error text, no invoice")
	void refused() {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(SHIP_AND_INVOICE)).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"code\":\"Application_DialogException\",\"message\":\"There is nothing to"
								+ " post.\"}}"));
		ErpInvoiceResult result = connector.requestInvoice("CV-000001", "1234567A/M/000", null);
		server.verify();
		assertFalse(result.isSuccess());
		assertNull(result.getInvoiceNo());
		assertNull(result.getPdf());
		assertTrue(result.getMessage().startsWith("parafendri: asking the invoice of sales order CV-000001 from"
				+ " shipAndInvoice failed: HTTP 400 "), result.getMessage());
		assertTrue(result.getMessage().endsWith(": Application_DialogException: There is nothing to post."),
				result.getMessage());
		assertTrue(result.getWarnings().isEmpty());
	}

	@Test
	@DisplayName("The lookup fails (503): nothing posted, a failure")
	void lookupFails() {
		server.expect(requestTo(LOOKUP)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		ErpInvoiceResult result = connector.requestInvoice("CV-000001", "1234567A/M/000", null);
		server.verify();
		assertFalse(result.isSuccess());
		assertTrue(result.getMessage().startsWith("parafendri: reading the page shipAndInvoice failed: HTTP 503"),
				result.getMessage());
	}

	@Test
	@DisplayName("An answer without a PDF: the invoice number kept, said in the message; no fiscal field given, no warning")
	void noPdf() throws IOException {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(SHIP_AND_INVOICE)).andRespond(withSuccess(sample("shipAndInvoice-answer.json")
				.replaceAll("\"invoicePdfBase64\": \"[^\"]*\"", "\"invoicePdfBase64\": \"\""), MediaType.APPLICATION_JSON));
		ErpInvoiceResult result = connector.requestInvoice("CV-000001", null, null);
		server.verify();
		assertTrue(result.isSuccess());
		assertEquals("FV-000001", result.getInvoiceNo());
		assertNull(result.getPdf());
		assertEquals("parafendri: sales order CV-000001 shipped and invoiced as FV-000001; no PDF in the answer",
				result.getMessage());
		assertTrue(result.getWarnings().isEmpty());
	}

	@Test
	@DisplayName("An answer without an invoice number: a failure; no sales order number: nothing sent")
	void noNumbers() {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(SHIP_AND_INVOICE)).andRespond(withSuccess("{\"orderNo\":\"CV-000001\"}",
				MediaType.APPLICATION_JSON));
		ErpInvoiceResult result = connector.requestInvoice("CV-000001", null, null);
		assertFalse(result.isSuccess());
		assertEquals("parafendri: Business Central answered the invoice of sales order CV-000001 without an invoice"
				+ " number", result.getMessage());
		ErpInvoiceResult none = connector.requestInvoice(" ", "1234567A/M/000", null);
		server.verify();
		assertFalse(none.isSuccess());
		assertEquals("parafendri: no invoice asked: the ticket has no sales order number", none.getMessage());
	}
}
