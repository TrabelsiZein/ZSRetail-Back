package com.digithink.zsretail.connector.zen.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.zen.client.ZenReadException;
import com.digithink.zsretail.connector.zen.client.ZenRestClient;
import com.digithink.zsretail.connector.zen.config.ZenConfig;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.connector.ZenConnector;
import com.digithink.zsretail.erp.api.ErpNotSupportedException;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 3.1.1, the stores' orders in ZEN's NAV against a mock server (no live call), with the real RestTemplate of the
 * settings: the variant stock (a plain array or OData's value), the lookup by externalDocumentNo before the POST (an
 * order found is reused, nothing posted; a lookup that fails posts nothing), the body of the POST and its answer, the
 * pages' addresses from erp.zen.variantstock.path and erp.zen.salesorders.path, and the interceptor: a POST to the
 * sales orders page only.
 */
class ZenStoreOrdersTest {

	static final String COMPANY_ID = "89c8b192-3f2e-f111-9f24-7ced8db3f120";
	static final String ROOT = "http://nav.test:7158/ZEN/api/sat/zsretail/v1.0/companies(" + COMPANY_ID + ")/";
	static final String NUMBER = "B-2026-000001";
	static final String LOOKUP = ROOT + "salesOrders?$filter=externalDocumentNo%20eq%20'" + NUMBER + "'&$select=orderNo";

	private ZenProperties properties;
	private RestTemplate template;
	private MockRestServiceServer server;
	private ZenStoreOrders orders;

	@BeforeEach
	void setUp() {
		properties = new ZenProperties();
		properties.setBaseUrl("http://nav.test:7158/ZEN");
		properties.setCompany("ZEN");
		properties.setCompanyId(COMPANY_ID);
		properties.setUsername("user");
		properties.setPassword("secret");
		build();
	}

	private void build() {
		template = ZenConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		orders = new ZenStoreOrders(new ZenRestClient(template, properties));
	}

	static ErpSalesOrderDTO order() {
		ErpSalesOrderDTO order = new ErpSalesOrderDTO();
		order.setLocationCode("B");
		order.setCustomerNo("C-0001");
		order.setExternalDocumentNo(NUMBER);
		order.setOrderDate(LocalDate.of(2026, 10, 27));
		order.getLines().add(new ErpSalesOrderDTO.Line("70061", "S", new BigDecimal("2")));
		order.getLines().add(new ErpSalesOrderDTO.Line("BOUGIE", "", new BigDecimal("1.5")));
		return order;
	}

	// ─── Variant stock ───────────────────────────────────────────

	@Test
	@DisplayName("Variant stock: GET variantStock?itemNo=&locationCode=, a plain array or OData's value, a row without item left out")
	void variantStock() {
		server.expect(requestTo(ROOT + "variantStock?itemNo=70061&locationCode=B")).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("[{\"itemNo\":\"70061\",\"variantCode\":\"S\",\"quantity\":4},"
						+ "{\"itemNo\":\"70061\",\"variantCode\":\"L\",\"quantity\":0.5},{\"variantCode\":\"X\"}]",
						MediaType.APPLICATION_JSON));
		List<ErpVariantStockDTO> rows = orders.variantStock(" 70061 ", "B");
		assertEquals(2, rows.size());
		assertEquals("S", rows.get(0).getVariantCode());
		assertEquals(0, new BigDecimal("4").compareTo(rows.get(0).getQuantity()));
		assertEquals(0, new BigDecimal("0.5").compareTo(rows.get(1).getQuantity()));

		server.reset();
		server.expect(requestTo(ROOT + "variantStock?itemNo=70061&locationCode=B"))
				.andRespond(withSuccess("{\"value\":[{\"itemNo\":\"70061\",\"variantCode\":\"\",\"quantity\":7}]}",
						MediaType.APPLICATION_JSON));
		rows = orders.variantStock("70061", "B");
		assertEquals("", rows.get(0).getVariantCode());
		assertEquals(0, new BigDecimal("7").compareTo(rows.get(0).getQuantity()));
	}

	@Test
	@DisplayName("Variant stock failing: ZenReadException naming the page and the status; the path from erp.zen.variantstock.path")
	void variantStockFails() {
		properties.getVariantstock().setPath("https://zen.example/franchise/variantStockByLocation");
		build();
		server.expect(requestTo("https://zen.example/franchise/variantStockByLocation?itemNo=70061&locationCode=B"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		ZenReadException e = assertThrows(ZenReadException.class, () -> orders.variantStock("70061", "B"));
		assertTrue(e.getMessage().startsWith("zen: reading the page https://zen.example/franchise/variantStockByLocation"
				+ " failed: HTTP 500"), e.getMessage());
	}

	// ─── Sales orders ────────────────────────────────────────────

	@Test
	@DisplayName("Posted: the lookup finds none, then POST salesOrders with the contract's body; success with NAV's orderNo")
	void posted() {
		server.expect(requestTo(LOOKUP)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(ROOT + "salesOrders")).andExpect(method(HttpMethod.POST))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.locationCode").value("B")).andExpect(jsonPath("$.customerNo").value("C-0001"))
				.andExpect(jsonPath("$.externalDocumentNo").value(NUMBER))
				.andExpect(jsonPath("$.orderDate").value("2026-10-27"))
				.andExpect(jsonPath("$.lines[0].itemNo").value("70061"))
				.andExpect(jsonPath("$.lines[0].variantCode").value("S"))
				.andExpect(jsonPath("$.lines[0].quantity").value(2))
				.andExpect(jsonPath("$.lines[1].variantCode").value(""))
				.andExpect(jsonPath("$.lines[1].quantity").value(1.5))
				.andRespond(withSuccess("{\"orderNo\":\"SO-0042\"}", MediaType.APPLICATION_JSON));
		ErpOperationResult result = orders.post(order());
		server.verify();
		assertTrue(result.isSuccess(), result.getMessage());
		assertEquals("SO-0042", result.getExternalReference());
		assertEquals(ROOT + "salesOrders", result.getUrl());
		assertEquals(NUMBER, ((JsonNode) result.getActualRequestPayload()).get("externalDocumentNo").asText());
	}

	@Test
	@DisplayName("Already in NAV (an answer lost): the lookup's order reused, nothing posted")
	void reused() {
		server.expect(requestTo(LOOKUP))
				.andRespond(withSuccess("{\"value\":[{\"orderNo\":\"SO-0041\"}]}", MediaType.APPLICATION_JSON));
		ErpOperationResult result = orders.post(order());
		server.verify(); // no POST expected
		assertTrue(result.isSuccess());
		assertEquals("SO-0041", result.getExternalReference());
		assertTrue(String.valueOf(result.getActualResponsePayload()).contains("reused"));
	}

	@Test
	@DisplayName("The lookup fails (the page cannot filter on externalDocumentNo): a failure, nothing posted")
	void lookupRequired() {
		server.expect(requestTo(LOOKUP)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.body("{\"error\":{\"message\":\"filter not supported\"}}").contentType(MediaType.APPLICATION_JSON));
		ErpOperationResult result = orders.post(order());
		server.verify();
		assertFalse(result.isSuccess());
		assertTrue(result.getMessage().startsWith("zen: reading the page salesOrders failed: HTTP 400"), result.getMessage());
		assertTrue(result.getMessage().contains("filter not supported"), result.getMessage());
		assertEquals(ROOT + "salesOrders", result.getUrl());
	}

	@Test
	@DisplayName("A POST refused by NAV, or answered without orderNo: a failure with the reason")
	void postFailures() {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(ROOT + "salesOrders")).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).body("Customer C-0001 is blocked"));
		ErpOperationResult refused = orders.post(order());
		assertFalse(refused.isSuccess());
		assertTrue(refused.getMessage().startsWith("zen: posting to the page salesOrders failed: HTTP 400"),
				refused.getMessage());
		assertTrue(refused.getMessage().contains("Customer C-0001 is blocked"));

		server.reset();
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(ROOT + "salesOrders")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		ErpOperationResult noNumber = orders.post(order());
		assertFalse(noNumber.isSuccess());
		assertEquals("zen: NAV answered the order " + NUMBER + " without its orderNo", noNumber.getMessage());

		ErpSalesOrderDTO without = order();
		without.setExternalDocumentNo(" ");
		assertFalse(orders.post(without).isSuccess(), "never posted without its number");
	}

	// ─── The interceptor and the connector ───────────────────────

	@Test
	@DisplayName("The interceptor: a POST to the sales orders page only; anything else but GET refused before it leaves")
	void postOnlyToSalesOrders() {
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> template.postForObject(ROOT + "stockPoints", "{}", String.class));
		assertTrue(refused.getMessage().contains("only reads from the ERP (GET)"), refused.getMessage());
		assertThrows(IllegalStateException.class, () -> template.put(ROOT + "salesOrders", "{}"), "a PUT, even there");
		assertEquals("/salesOrders", ZenConfig.GetOnly.postPathOf(" /salesOrders/ "));
		assertEquals("/franchise/orders", ZenConfig.GetOnly.postPathOf("https://zen.example/franchise/orders"));
		assertNull(ZenConfig.GetOnly.postPathOf(" "));
	}

	@Test
	@DisplayName("ZenConnector answers from here; without the orders (the invoices' tests) the defaults: not supported")
	void connector() {
		server.expect(requestTo(LOOKUP))
				.andRespond(withSuccess("{\"value\":[{\"orderNo\":\"SO-0041\"}]}", MediaType.APPLICATION_JSON));
		assertEquals("SO-0041", new ZenConnector(null, orders).postSalesOrder(order()).getExternalReference());
		ZenConnector withoutOrders = new ZenConnector(null);
		assertFalse(withoutOrders.postSalesOrder(order()).isSuccess());
		assertThrows(ErpNotSupportedException.class, () -> withoutOrders.fetchVariantStock("70061", "B"));
	}
}
