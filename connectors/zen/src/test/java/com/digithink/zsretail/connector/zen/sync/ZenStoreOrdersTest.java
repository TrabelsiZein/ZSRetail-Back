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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.zen.client.ZenReadException;
import com.digithink.zsretail.connector.zen.client.ZenRestClient;
import com.digithink.zsretail.connector.zen.config.ZenConfig;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.connector.ZenConnector;
import com.digithink.zsretail.connector.zen.mapper.ZenOrderFields;
import com.digithink.zsretail.erp.api.ErpNotSupportedException;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.1.1, the stores' orders in ZEN's NAV against a mock server (no live call), with the real RestTemplate of the
 * settings: the variant stock on ZEN's real page infoArticles (the sample src/test/resources/zen/infoArticles.json, read
 * from ZEN's test NAV on 2026-10-27: 43ZBPND01 for the client 4110001, twelve variants), the lookup by externalDocumentNo before the POST (an
 * order found is reused, nothing posted; a lookup that fails posts nothing), the body of the POST and its answer, the
 * pages' addresses from erp.zen.variantstock.path and erp.zen.salesorders.path, and the interceptor: a POST to the
 * sales orders page only.
 */
class ZenStoreOrdersTest {

	static final String COMPANY_ID = "89c8b192-3f2e-f111-9f24-7ced8db3f120";
	static final String ROOT = "http://nav.test:7158/ZEN/api/sat/zsretail/v1.0/companies(" + COMPANY_ID + ")/";
	static final String NUMBER = "B-2026-000001";
	static final String LOOKUP = ROOT + "salesOrders?$filter=externalDocumentNo%20eq%20'" + NUMBER + "'&$select=orderNo";
	static final String STOCK = ROOT + "infoArticles?$filter=article%20eq%20'43ZBPND01'%20and%20client%20eq%20'4110001'";

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

	static String sample() throws IOException {
		try (InputStream in = ZenStoreOrdersTest.class.getResourceAsStream("/zen/infoArticles.json")) {
			return new String(StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("Variant stock on the sample of infoArticles: filtered on article and client; the quantity disponible (never"
			+ " stock), the size order ordreTaille, the details in order")
	void variantStock() throws IOException {
		assertEquals("infoArticles", new ZenProperties().getVariantstock().getPath(), "the default page");
		server.expect(requestTo(STOCK)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(sample(), MediaType.APPLICATION_JSON));
		List<ErpVariantStockDTO> rows = orders.variantStock(" 43ZBPND01 ", " 4110001 ");
		server.verify();
		assertEquals(12, rows.size());
		ErpVariantStockDTO first = rows.get(0);
		assertEquals("43ZBPND01", first.getItemNo());
		assertEquals("002", first.getVariantCode());
		assertEquals(0, new BigDecimal("203").compareTo(first.getQuantity()), "disponible, not stock (48)");
		assertEquals(Integer.valueOf(160), first.getSizeOrder());
		assertEquals(Arrays.asList("Prix unitaire", "Remise %", "Prix net", "Stock", "Qt\u00e9 vrac", "Vrac autoris\u00e9",
				"Contenance s\u00e9rie", "Colis possibles", "Calcul\u00e9 le"), new ArrayList<>(first.getDetails().keySet()));
		assertEquals("0", first.getDetails().get("Prix net"));
		assertEquals("48", first.getDetails().get("Stock"));
		assertEquals("2", first.getDetails().get("Qt\u00e9 vrac"));
		assertEquals("Non", first.getDetails().get("Vrac autoris\u00e9"));
		assertEquals("10", first.getDetails().get("Contenance s\u00e9rie"));
		assertEquals("101", first.getDetails().get("Colis possibles"));
		assertEquals(OffsetDateTime.parse("2026-10-27T19:36:14.06Z").atZoneSameInstant(ZoneId.systemDefault())
				.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), first.getDetails().get("Calcul\u00e9 le"));
		ErpVariantStockDTO size36 = rows.stream().filter(row -> row.getVariantCode().equals("001")).findFirst().get();
		assertEquals(Integer.valueOf(220), size36.getSizeOrder(), "ZEN's order, as it gives it");
		assertEquals(0, new BigDecimal("203").compareTo(size36.getQuantity()));
	}

	@Test
	@DisplayName("The rows' edges: a quote doubled; no disponible: null (unknown); no article: left out; an empty detail"
			+ " left out; Oui; calculeLe in the zone, as it is when unreadable")
	void stockRowsEdges() throws IOException {
		assertEquals("article eq 'L''AMI' and client eq '4110001'", ZenOrderFields.stockFilter(" L'AMI ", "4110001 "));
		JsonNode answer = new ObjectMapper().readTree("{\"value\":[{\"article\":\"A\",\"codeVariante\":\"001\","
				+ "\"vracAutorise\":true,\"remisePourcent\":\"\",\"prixNet\":null,\"calculeLe\":\"2026-10-27T19:36:14.06Z\"},"
				+ "{\"codeVariante\":\"002\",\"disponible\":3},"
				+ "{\"article\":\"A\",\"codeVariante\":\"003\",\"disponible\":1.5,\"calculeLe\":\"hier\"},"
				+ "{\"article\":\"A\",\"codeVariante\":\"004\",\"disponible\":0}]}");
		List<ErpVariantStockDTO> rows = ZenOrderFields.stockRows(answer, ZoneId.of("Africa/Tunis"));
		assertEquals(3, rows.size(), "the row without article left out");
		assertNull(rows.get(0).getQuantity(), "no disponible: unknown, never 0");
		assertNull(rows.get(0).getSizeOrder());
		assertEquals(Arrays.asList("Vrac autoris\u00e9", "Calcul\u00e9 le"), new ArrayList<>(rows.get(0).getDetails().keySet()));
		assertEquals("Oui", rows.get(0).getDetails().get("Vrac autoris\u00e9"));
		assertEquals("27/10/2026 20:36", rows.get(0).getDetails().get("Calcul\u00e9 le"));
		assertEquals(0, new BigDecimal("1.5").compareTo(rows.get(1).getQuantity()));
		assertEquals(Collections.singletonMap("Calcul\u00e9 le", "hier"), rows.get(1).getDetails());
		assertEquals(0, BigDecimal.ZERO.compareTo(rows.get(2).getQuantity()));
		assertNull(rows.get(2).getDetails(), "nothing to tell: no details");
	}

	@Test
	@DisplayName("Variant stock failing: ZenReadException naming the page and the status; the path from erp.zen.variantstock.path")
	void variantStockFails() {
		properties.getVariantstock().setPath("https://zen.example/franchise/infoArticles");
		build();
		server.expect(requestTo("https://zen.example/franchise/infoArticles?$filter=article%20eq%20'70061'%20and%20client"
				+ "%20eq%20'C-0001'")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		ZenReadException e = assertThrows(ZenReadException.class, () -> orders.variantStock("70061", "C-0001"));
		assertTrue(e.getMessage().startsWith("zen: reading the page https://zen.example/franchise/infoArticles"
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
		assertThrows(ErpNotSupportedException.class, () -> withoutOrders.fetchVariantStock("70061", "B", "C-0001"));
	}
}
