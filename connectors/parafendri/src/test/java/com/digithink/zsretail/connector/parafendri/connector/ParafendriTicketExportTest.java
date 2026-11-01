package com.digithink.zsretail.connector.parafendri.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.parafendri.ParafendriTickets;
import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.connector.parafendri.config.ParafendriConfig;
import com.digithink.zsretail.connector.parafendri.config.ParafendriProperties;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriSalesOrder;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriSalesOrderRow;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;

/**
 * ParaFendri, step 6: pushTicket against a mock server (no live POST). The ticket is looked up first by its number:
 * found, its order answers (never posted twice); else the point de vente's customerNo (read once) and one POST with
 * the lines inside (the sample salesOrder-request.json), Business Central's number and orderStatus in the result; a
 * refusal answers Business Central's own error text; nothing is sent without DEFAULT_LOCATION or a customerNo.
 */
class ParafendriTicketExportTest {

	static final String COMPANY = "http://bc.test:7048/BC280/api/sat/zsretail/v1.0/companies("
			+ ParafendriConnectorTest.COMPANY_ID + ")/";
	static final String SALES_ORDERS = COMPANY + "salesOrders";
	static final String LOOKUP = SALES_ORDERS + "?$filter=ticketNo%20eq%20'" + ParafendriTickets.TICKET_NO + "'";
	static final String CUSTOMER = COMPANY + "stockPoints?$filter=locationCode%20eq%20'PV-GREMDA'&$top=1";
	static final String CUSTOMER_ROW = "{\"value\":[{\"locationCode\":\"PV-GREMDA\",\"itemNo\":\"X\","
			+ "\"customerNo\":\"C-GREMDA\"}]}";
	static final String NONE = "{\"value\":[]}";

	private MockRestServiceServer server;
	private ParafendriConnector connector;

	@BeforeEach
	void setUp() {
		connector = connector(() -> "PV-GREMDA");
	}

	private ParafendriConnector connector(java.util.function.Supplier<String> location) {
		ParafendriProperties properties = new ParafendriProperties();
		properties.setBaseUrl("http://bc.test:7048/BC280");
		properties.setCompanyId(ParafendriConnectorTest.COMPANY_ID);
		properties.setUsername("user");
		properties.setPassword("secret");
		RestTemplate template = ParafendriConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		return new ParafendriConnector(new ParafendriRestClient(template, properties),
				ParafendriConnectorTest.location(location));
	}

	private static String sample(String name) throws IOException {
		try (InputStream in = ParafendriTicketExportTest.class.getResourceAsStream("/parafendri/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("A new ticket: looked up (none), the customerNo read, one POST of header and lines; BC's number and status")
	void posts() throws IOException {
		server.expect(requestTo(LOOKUP)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(CUSTOMER)).andRespond(withSuccess(CUSTOMER_ROW, MediaType.APPLICATION_JSON));
		server.expect(requestTo(SALES_ORDERS)).andExpect(method(HttpMethod.POST))
				.andExpect(content().json(sample("salesOrder-request.json"), true))
				.andRespond(withSuccess(sample("salesOrder-answer.json"), MediaType.APPLICATION_JSON));
		ErpOperationResult result = connector.pushTicket(ParafendriTickets.ticket());
		server.verify();
		assertTrue(result.isSuccess(), result.getMessage());
		assertEquals("CV-000001", result.getExternalReference());
		assertEquals("parafendri: ticket PV-GREMDA251031001 posted as sales order CV-000001 (orderStatus Entered, BC"
				+ " amountIncludingVAT 70, our total TTC 70.0)",
				result.getMessage());
		assertEquals(SALES_ORDERS, result.getUrl());
		assertTrue(result.getActualRequestPayload() instanceof ParafendriSalesOrder, "the body sent, for the log");
		assertEquals("Entered", ((ParafendriSalesOrderRow) result.getActualResponsePayload()).getOrderStatus());
		assertEquals(new BigDecimal("70"), ((ParafendriSalesOrderRow) result.getActualResponsePayload())
				.getAmountIncludingVAT(), "read back from BC's answer");
	}

	@Test
	@DisplayName("A ticket already in Business Central: its order answers, nothing posted (never twice)")
	void alreadyThere() throws IOException {
		String row = "{\"value\":[" + sample("salesOrder-answer.json").replace("\"orderStatus\": \"Entered\"",
				"\"orderStatus\": \"Posted\"") + "]}";
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(row, MediaType.APPLICATION_JSON));
		ErpOperationResult result = connector.pushTicket(ParafendriTickets.ticket());
		server.verify();
		assertTrue(result.isSuccess());
		assertEquals("CV-000001", result.getExternalReference());
		assertEquals("parafendri: ticket PV-GREMDA251031001 is already in Business Central as sales order CV-000001"
				+ " (orderStatus Posted, BC amountIncludingVAT 70, our total TTC 70.0): not posted again", result.getMessage());
	}

	@Test
	@DisplayName("ticketNo not filterable in BC (400): posted anyway, the result carries the no-double warning")
	void guardUnavailable() throws IOException {
		server.expect(requestTo(LOOKUP)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":\"BadRequest_NotSupported\","
						+ "\"message\":\"Field 'ticketNo' is not filterable.  CorrelationId:  511f6922.\"}}"));
		server.expect(requestTo(CUSTOMER)).andRespond(withSuccess(CUSTOMER_ROW, MediaType.APPLICATION_JSON));
		server.expect(requestTo(SALES_ORDERS)).andExpect(method(HttpMethod.POST))
				.andExpect(content().json(sample("salesOrder-request.json"), true))
				.andRespond(withSuccess(sample("salesOrder-answer.json"), MediaType.APPLICATION_JSON));
		ErpOperationResult result = connector.pushTicket(ParafendriTickets.ticket());
		server.verify();
		assertTrue(result.isSuccess(), result.getMessage());
		assertEquals("CV-000001", result.getExternalReference());
		assertEquals(java.util.Collections.singletonList("no-double guard unavailable: ticketNo is not filterable in BC"
				+ " — ask the BC contact to make ticketNo a stored, filterable field"), result.getWarnings());
	}

	@Test
	@DisplayName("Another failure of the lookup (a 400 that is not 'not filterable', a 503): nothing posted, a failure")
	void lookupFails() {
		server.expect(requestTo(LOOKUP)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		ErpOperationResult result = connector.pushTicket(ParafendriTickets.ticket());
		server.verify();
		assertFalse(result.isSuccess());
		assertTrue(result.getMessage().startsWith("parafendri: reading the page salesOrders failed: HTTP 503"),
				result.getMessage());
		assertTrue(result.getWarnings().isEmpty());

		ParafendriConnector other = connector(() -> "PV-GREMDA");
		server.expect(requestTo(LOOKUP)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":\"BadRequest\","
						+ "\"message\":\"Invalid filter\"}}"));
		assertFalse(other.pushTicket(ParafendriTickets.ticket()).isSuccess());
		server.verify();
	}

	@Test
	@DisplayName("Business Central refuses the POST: a failure with its status and its own error text")
	void refused() throws IOException {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(CUSTOMER)).andRespond(withSuccess(CUSTOMER_ROW, MediaType.APPLICATION_JSON));
		server.expect(requestTo(SALES_ORDERS)).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"code\":\"Internal_RecordNotFound\",\"message\":\"The Item does not exist."
								+ " Identification fields and values: No.='TIMBRE'\"}}"));
		ErpOperationResult result = connector.pushTicket(ParafendriTickets.ticket());
		server.verify();
		assertFalse(result.isSuccess());
		assertTrue(result.getMessage().startsWith("parafendri: posting the ticket PV-GREMDA251031001 to salesOrders"
				+ " failed: HTTP 400 "), result.getMessage());
		assertTrue(result.getMessage().endsWith(": Internal_RecordNotFound: The Item does not exist. Identification"
				+ " fields and values: No.='TIMBRE'"), result.getMessage());
		assertTrue(result.getActualRequestPayload() instanceof ParafendriSalesOrder);
	}

	@Test
	@DisplayName("The customerNo read once for the location: a second ticket looks itself up and posts, no stockPoints read")
	void customerNoOnce() throws IOException {
		ErpTicketDTO second = ParafendriTickets.ticket();
		second.setTicketNumber("PV-GREMDA251031002");
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(CUSTOMER)).andRespond(withSuccess(CUSTOMER_ROW, MediaType.APPLICATION_JSON));
		server.expect(requestTo(SALES_ORDERS)).andRespond(withSuccess(sample("salesOrder-answer.json"),
				MediaType.APPLICATION_JSON));
		server.expect(requestTo(SALES_ORDERS + "?$filter=ticketNo%20eq%20'PV-GREMDA251031002'"))
				.andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(SALES_ORDERS)).andRespond(withSuccess(
				sample("salesOrder-answer.json").replace("CV-000001", "CV-000002"), MediaType.APPLICATION_JSON));
		assertEquals("CV-000001", connector.pushTicket(ParafendriTickets.ticket()).getExternalReference());
		assertEquals("CV-000002", connector.pushTicket(second).getExternalReference());
		server.verify();
	}

	@Test
	@DisplayName("No customerNo on the location's stockPoints: a failure, nothing posted")
	void noCustomer() {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(CUSTOMER)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		ErpTicketDTO ticket = ParafendriTickets.ticket();
		ErpOperationResult result = connector.pushTicket(ticket);
		server.verify();
		assertFalse(result.isSuccess());
		assertEquals("parafendri: the ticket PV-GREMDA251031001 was not sent: no customerNo on the stockPoints rows of"
				+ " location PV-GREMDA", result.getMessage());
		assertSame(ticket, result.getActualRequestPayload());
	}

	@Test
	@DisplayName("No DEFAULT_LOCATION: a failure, nothing sent")
	void noLocation() {
		ParafendriConnector without = connector(() -> null);
		ErpOperationResult result = without.pushTicket(ParafendriTickets.ticket());
		server.verify();
		assertFalse(result.isSuccess());
		assertEquals("parafendri: the ticket was not sent: DEFAULT_LOCATION is not configured", result.getMessage());
	}
}
