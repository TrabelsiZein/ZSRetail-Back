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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.zen.client.ZenReadException;
import com.digithink.zsretail.connector.zen.client.ZenRestClient;
import com.digithink.zsretail.connector.zen.config.ZenConfig;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.connector.ZenConnector;
import com.digithink.zsretail.connector.zen.mapper.ZenOrderFields;
import com.digithink.zsretail.erp.api.ErpNotSupportedException;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderResult;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 3.1.1, the stores' orders in ZEN's NAV against a mock server (no live call), with the real RestTemplate of the
 * settings: the variant stock on ZEN's page infoArticles (the sample src/test/resources/zen/infoArticles.json, read from
 * ZEN's test NAV on 2026-10-27: 43ZBPND01 for the client 4110001, twelve variants); the sales orders on its page
 * commandesVente (the sample src/test/resources/zen/commandesVente.json, CV26000003 read with $expand=lignesCommande
 * from ZEN's test NAV on 2026-10-27: three lines of 43ZBPND01, quantite 10 each): the lookup by referenceZS with
 * the lines before any POST (an order found is reused, its missing lines posted; a lookup that fails posts nothing), the
 * deep insert and its body, the header then one POST per line when NAV refuses the nested lines, NAV's answer (numero,
 * message, quantite and message per line), and the interceptor: a POST to commandesVente and its orders' lines only.
 */
class ZenStoreOrdersTest {

	static final String COMPANY_ID = "89c8b192-3f2e-f111-9f24-7ced8db3f120";
	static final String ROOT = "http://nav.test:7158/ZEN/api/sat/zsretail/v1.0/companies(" + COMPANY_ID + ")/";
	static final String NUMBER = "ZS01-2026-000001";
	static final String LOOKUP = ROOT + "commandesVente?$filter=referenceZS%20eq%20'" + NUMBER
			+ "'&$expand=lignesCommande";
	static final String ORDERS = ROOT + "commandesVente";
	/** The id of CV26000003 in the sample. */
	static final String ID = "2e707462-ded1-f111-9db8-ad3d0c589c9c";
	static final String BY_ID = ROOT + "commandesVente(" + ID + ")?$expand=lignesCommande";
	static final String LINES = ROOT + "commandesVente(" + ID + ")/lignesCommande";
	static final String STOCK = ROOT + "infoArticles?$filter=article%20eq%20'43ZBPND01'%20and%20client%20eq%20'4110001'";
	static final String NONE = "{\"value\":[]}";
	static final ObjectMapper JSON = new ObjectMapper();

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
		orders = new ZenStoreOrders(new ZenRestClient(template, properties), properties);
	}

	/** The store's order: the three variants of the sample, 12 pieces asked of 002 (NAV accepted 10). */
	static ErpSalesOrderDTO order() {
		ErpSalesOrderDTO order = new ErpSalesOrderDTO();
		order.setLocationCode("ZS01");
		order.setCustomerNo("4110001");
		order.setExternalDocumentNo(NUMBER);
		order.setOrderDate(LocalDate.of(2026, 10, 27));
		order.getLines().add(new ErpSalesOrderDTO.Line("43ZBPND01", "001", new BigDecimal("10")));
		order.getLines().add(new ErpSalesOrderDTO.Line("43ZBPND01", "002", new BigDecimal("12")));
		order.getLines().add(new ErpSalesOrderDTO.Line("43ZBPND01", "003", new BigDecimal("10")));
		return order;
	}

	/** The lookup's answer: the sample of commandesVente (under "value"). */
	static String found() throws IOException {
		try (InputStream in = ZenStoreOrdersTest.class.getResourceAsStream("/zen/commandesVente.json")) {
			return new String(StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
		}
	}

	/**
	 * CV26000003 alone (GET by its id): the sample's row, with our order number in referenceZS (the sample was made by
	 * hand in NAV: its referenceZS is empty).
	 */
	static ObjectNode navOrder() throws IOException {
		return ((ObjectNode) JSON.readTree(found()).get("value").get(0)).put("referenceZS", NUMBER);
	}

	/** The lookup's answer when NAV has the order: the sample's row under "value". */
	static String foundOurs() throws IOException {
		return "{\"value\":[" + navOrder() + "]}";
	}

	/** The order as a POST answers it: the header without its lines, with this message. */
	static String header(String message) throws IOException {
		ObjectNode header = navOrder();
		header.remove("lignesCommande");
		header.put("message", message);
		return header.toString();
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
				"Contenance s\u00e9rie", "Colis possibles", "Types de s\u00e9rie", "Calcul\u00e9 le"),
				new ArrayList<>(first.getDetails().keySet()));
		assertEquals("2", first.getDetails().get("Types de s\u00e9rie"), "qteSerieType");
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
	@DisplayName("Variant stock failing: ZenReadException naming the page and the status")
	void variantStockFails() {
		server.expect(requestTo(ROOT + "infoArticles?$filter=article%20eq%20'70061'%20and%20client%20eq%20'C-0001'"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		ZenReadException e = assertThrows(ZenReadException.class, () -> orders.variantStock("70061", "C-0001"));
		assertTrue(e.getMessage().startsWith("zen: reading the page infoArticles failed: HTTP 500"), e.getMessage());
	}

	// ─── Sales orders ────────────────────────────────────────────

	@Test
	@DisplayName("The sample read back: numero, id, no message; per line article, variant, quantite (accepted), no message")
	void sampleAnswer() throws IOException {
		JsonNode order = ZenOrderFields.orderOf(JSON.readTree(found()));
		assertEquals("CV26000003", ZenOrderFields.orderNoOf(order));
		assertEquals(ID, ZenOrderFields.orderIdOf(order));
		assertTrue(ZenOrderFields.hasLines(order));
		ErpSalesOrderResult result = new ErpSalesOrderResult();
		ZenOrderFields.answerInto(order, result);
		assertNull(result.getOrderMessage(), "an empty message: none");
		assertEquals(3, result.getLines().size());
		ErpSalesOrderResult.Line first = result.getLines().get(0);
		assertEquals("43ZBPND01", first.getItemNo());
		assertEquals("001", first.getVariantCode());
		assertEquals(0, new BigDecimal("10").compareTo(first.getAcceptedQty()), "quantite, not quantiteDemandee (0)");
		assertNull(first.getMessage());
		assertNull(ZenOrderFields.orderOf(JSON.readTree(NONE)), "none found");
		assertEquals("referenceZS eq 'ZS01-2026-0''1'", ZenOrderFields.lookupFilter(" ZS01-2026-0'1 "), "a quote doubled");
	}

	@Test
	@DisplayName("Posted in one call (deep insert): the lookup finds none, POST commandesVente with the header (the order"
			+ " number in referenceZS, no numeroDocExterne, commandeZS true, no store code) and its lines nested (mode vrac by"
			+ " default, no dateLivraisonSouhaitee, no nbColis), then the order read with its lines: success with numero, NAV's message"
			+ " and per line the quantity accepted")
	void postedDeepInsert() throws IOException {
		server.expect(requestTo(LOOKUP)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.referenceZS").value(NUMBER))
				.andExpect(jsonPath("$.numeroDocExterne").doesNotExist())
				.andExpect(jsonPath("$.commandeZS").value(true))
				.andExpect(jsonPath("$.client").value("4110001"))
				.andExpect(jsonPath("$.dateCommande").value("2026-10-27"))
				.andExpect(jsonPath("$.dateLivraisonSouhaitee").doesNotExist())
				.andExpect(jsonPath("$.lignesCommande.length()").value(3))
				.andExpect(jsonPath("$.lignesCommande[1].article").value("43ZBPND01"))
				.andExpect(jsonPath("$.lignesCommande[1].codeVariante").value("002"))
				.andExpect(jsonPath("$.lignesCommande[1].quantiteDemandee").value(12))
				.andExpect(jsonPath("$.lignesCommande[1].mode").value("vrac"))
				.andExpect(jsonPath("$.lignesCommande[1].nbColis").doesNotExist())
				.andRespond(withSuccess(header("Commande reçue"), MediaType.APPLICATION_JSON));
		server.expect(requestTo(BY_ID)).andExpect(method(HttpMethod.GET)).andRespond(
				withSuccess(navOrder().put("message", "Commande reçue").toString(), MediaType.APPLICATION_JSON));
		ErpSalesOrderResult result = orders.post(order());
		server.verify();
		assertTrue(result.isSuccess(), result.getMessage());
		assertEquals("CV26000003", result.getExternalReference());
		assertEquals(ORDERS, result.getUrl());
		assertEquals("NAV's order CV26000003 of " + NUMBER + ": posted with its 3 lines in one call (deep insert)",
				result.getMessage());
		JsonNode sent = (JsonNode) result.getActualRequestPayload();
		assertEquals(NUMBER, sent.get("referenceZS").asText());
		assertFalse(sent.has("numeroDocExterne"), "read-only on ZEN's side: never sent");
		assertFalse(sent.toString().contains("ZS01\""), "the store code travels nowhere: " + sent);
		assertEquals("Commande reçue", result.getOrderMessage());
		assertEquals(3, result.getLines().size());
		assertEquals(0, new BigDecimal("10").compareTo(result.getLines().get(1).getAcceptedQty()), "12 asked, 10 accepted");
	}

	/** 3.1.1: the order with 3 colis of 43ZBPND01's variant 001 (its colour row) after its pieces (10 each: 30, informative). */
	static ErpSalesOrderDTO orderWithColis() {
		ErpSalesOrderDTO order = order();
		order.getLines().add(ErpSalesOrderDTO.Line.colis("43ZBPND01", "001", 3, new BigDecimal("30")));
		return order;
	}

	/** NAV's order with its colis line answered: mode colis, 2 colis of 3 accepted, a message. */
	static ObjectNode navOrderWithColis() throws IOException {
		ObjectNode order = navOrder();
		((ArrayNode) order.get("lignesCommande")).addObject().put("article", "43ZBPND01").put("codeVariante", "001")
				.put("mode", "colis").put("nbColis", 3).put("quantiteDemandee", 0).put("colisAcceptes", 2)
				.put("quantite", 20).put("message", "Colis partiel");
		return order;
	}

	@Test
	@DisplayName("3.1.1, a colis line: {article, codeVariante, mode colis, nbColis} (NAV requires the variant), no"
			+ " quantiteDemandee = nbColis x contenance (NAV wants it positive), whatever erp.zen.order.mode says; pieces lines"
			+ " keep mode vrac; NAV's answer of the colis"
			+ " line read as colis, with colisAcceptes and its message")
	void colisLine() throws IOException {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.lignesCommande.length()").value(4))
				.andExpect(jsonPath("$.lignesCommande[0].mode").value("vrac"))
				.andExpect(jsonPath("$.lignesCommande[3].article").value("43ZBPND01"))
				.andExpect(jsonPath("$.lignesCommande[3].mode").value("colis"))
				.andExpect(jsonPath("$.lignesCommande[3].nbColis").value(3))
				.andExpect(jsonPath("$.lignesCommande[3].codeVariante").value("001"))
				.andExpect(jsonPath("$.lignesCommande[3].quantiteDemandee").value(30))
				.andRespond(withSuccess(navOrderWithColis().toString(), MediaType.APPLICATION_JSON));
		ErpSalesOrderResult result = orders.post(orderWithColis());
		server.verify();
		assertTrue(result.isSuccess(), result.getMessage());
		assertEquals(4, result.getLines().size());
		assertFalse(result.getLines().get(0).isColis());
		assertNull(result.getLines().get(0).getAcceptedColis(), "a pieces line: no colis accepted (its colisAcceptes 0 is not read)");
		ErpSalesOrderResult.Line colis = result.getLines().get(3);
		assertTrue(colis.isColis());
		assertEquals(Integer.valueOf(2), colis.getAcceptedColis());
		assertEquals("Colis partiel", colis.getMessage());

		ObjectNode withPieces = ZenOrderFields.lineBody(ErpSalesOrderDTO.Line.colis("43ZBPND01", "001", 3,
				new BigDecimal("15")), "vrac");
		assertEquals("{\"article\":\"43ZBPND01\",\"codeVariante\":\"001\",\"mode\":\"colis\",\"nbColis\":3,"
				+ "\"quantiteDemandee\":15}", withPieces.toString(), "3 colis of 5 pieces");
		ObjectNode body = ZenOrderFields.lineBody(ErpSalesOrderDTO.Line.colis("43ZBPND01", "001", 3, null), "vrac");
		assertEquals("{\"article\":\"43ZBPND01\",\"codeVariante\":\"001\",\"mode\":\"colis\",\"nbColis\":3}",
				body.toString());
	}

	@Test
	@DisplayName("3.1.1, completing an order: a colis line is missing until NAV has a colis line of its variant (never"
			+ " taken for the pieces line of that variant), then not posted again; another variant's colis still missing")
	void colisMissing() throws IOException {
		assertEquals(Collections.singletonList(true),
				ZenOrderFields.missingLines(orderWithColis(), navOrder()).stream().map(ErpSalesOrderDTO.Line::isColis)
						.collect(Collectors.toList()), "NAV has the pieces of 001 only: its colis line is missing");
		assertTrue(ZenOrderFields.missingLines(orderWithColis(), navOrderWithColis()).isEmpty());
		ErpSalesOrderDTO otherColour = new ErpSalesOrderDTO();
		otherColour.getLines().add(ErpSalesOrderDTO.Line.colis("43ZBPND01", "007", 1, null));
		assertEquals(1, ZenOrderFields.missingLines(otherColour, navOrderWithColis()).size(),
				"the colis of 001 are not those of 007");
	}

	@Test
	@DisplayName("erp.zen.order.mode set: every line sends its mode (trimmed); set empty: no mode; an answer with its lines"
			+ " is not read again")
	void mode() throws IOException {
		assertEquals("vrac", new ZenProperties().getOrder().getMode(), "the default");
		properties.getOrder().setMode(" colis ");
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.lignesCommande[0].mode").value("colis"))
				.andExpect(jsonPath("$.lignesCommande[2].mode").value("colis"))
				.andRespond(withSuccess(navOrder().toString(), MediaType.APPLICATION_JSON));
		assertTrue(orders.post(order()).isSuccess());
		server.verify();

		server.reset();
		properties.getOrder().setMode("");
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.lignesCommande[0].mode").doesNotExist())
				.andRespond(withSuccess(navOrder().toString(), MediaType.APPLICATION_JSON));
		assertTrue(orders.post(order()).isSuccess());
		server.verify();
	}

	@Test
	@DisplayName("NAV refuses the nested lines (a 4xx naming lignesCommande): the header alone, then one POST per line on"
			+ " commandesVente(id)/lignesCommande, then the order read with its lines")
	void headerThenLines() throws IOException {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST)).andExpect(jsonPath("$.lignesCommande").exists())
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(
						"{\"error\":{\"code\":\"BadRequest\",\"message\":\"Deep insert is not supported for lignesCommande\"}}"));
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.lignesCommande").doesNotExist())
				.andExpect(jsonPath("$.referenceZS").value(NUMBER))
				.andExpect(jsonPath("$.commandeZS").value(true))
				.andExpect(jsonPath("$.numeroDocExterne").doesNotExist())
				.andRespond(withSuccess(header(""), MediaType.APPLICATION_JSON));
		for (String variant : Arrays.asList("001", "002", "003")) {
			server.expect(requestTo(LINES)).andExpect(method(HttpMethod.POST))
					.andExpect(jsonPath("$.article").value("43ZBPND01"))
					.andExpect(jsonPath("$.codeVariante").value(variant))
					.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		}
		server.expect(requestTo(BY_ID)).andRespond(withSuccess(navOrder().toString(), MediaType.APPLICATION_JSON));
		ErpSalesOrderResult result = orders.post(order());
		server.verify();
		assertTrue(result.isSuccess(), result.getMessage());
		assertEquals("CV26000003", result.getExternalReference());
		assertTrue(result.getMessage().endsWith("NAV refused the nested lines: header then 3 lines posted one by one"),
				result.getMessage());
		assertEquals(3, result.getLines().size());
	}

	@Test
	@DisplayName("Already in NAV with all its lines (an answer lost): reused with the lines' answer, nothing posted")
	void reused() throws IOException {
		server.expect(requestTo(LOOKUP)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(foundOurs(), MediaType.APPLICATION_JSON));
		ErpSalesOrderResult result = orders.post(order());
		server.verify(); // no POST expected
		assertTrue(result.isSuccess());
		assertEquals("CV26000003", result.getExternalReference());
		assertTrue(result.getMessage().endsWith("already exists: reused, nothing posted"), result.getMessage());
		assertEquals(3, result.getLines().size());
	}

	@Test
	@DisplayName("Already in NAV with only some of its lines (a posting stopped half-way): only the missing lines posted,"
			+ " then the order read again")
	void completed() throws IOException {
		ObjectNode partial = navOrder();
		ArrayNode lines = (ArrayNode) partial.get("lignesCommande");
		lines.remove(2);
		lines.remove(1);
		server.expect(requestTo(LOOKUP))
				.andRespond(withSuccess("{\"value\":[" + partial + "]}", MediaType.APPLICATION_JSON));
		for (String variant : Arrays.asList("002", "003")) {
			server.expect(requestTo(LINES)).andExpect(method(HttpMethod.POST))
					.andExpect(jsonPath("$.codeVariante").value(variant))
					.andExpect(jsonPath("$.quantiteDemandee").value("002".equals(variant) ? 12 : 10))
					.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		}
		server.expect(requestTo(BY_ID)).andRespond(withSuccess(navOrder().toString(), MediaType.APPLICATION_JSON));
		ErpSalesOrderResult result = orders.post(order());
		server.verify();
		assertTrue(result.isSuccess(), result.getMessage());
		assertTrue(result.getMessage().endsWith("already exists: reused, its 2 missing lines posted"), result.getMessage());
		assertEquals(3, result.getLines().size());
	}

	@Test
	@DisplayName("The lookup fails: a failure, nothing posted")
	void lookupRequired() {
		server.expect(requestTo(LOOKUP)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.body("{\"error\":{\"message\":\"filter not supported\"}}").contentType(MediaType.APPLICATION_JSON));
		ErpSalesOrderResult result = orders.post(order());
		server.verify();
		assertFalse(result.isSuccess());
		assertTrue(result.getMessage().startsWith("zen: reading the page commandesVente failed: HTTP 400"),
				result.getMessage());
		assertTrue(result.getMessage().contains("filter not supported"), result.getMessage());
		assertEquals(ORDERS, result.getUrl());
	}

	/** NAV's refusal of the lookup's filter on referenceZS. */
	static final String NOT_FILTERABLE = "{\"error\":{\"code\":\"BadRequest_InvalidRequestUrl\",\"message\":\"Field"
			+ " 'referenceZS' is not filterable.  CorrelationId:  0d3f4a2e-0000-0000-0000-000000000000.\"}}";

	@Test
	@DisplayName("NAV refuses the filter on referenceZS (not filterable): the lookup skipped, the order posted, the"
			+ " communications log message says the duplicate check was skipped")
	void lookupNotFilterable() throws IOException {
		server.expect(requestTo(LOOKUP)).andExpect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON).body(NOT_FILTERABLE));
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.referenceZS").value(NUMBER))
				.andExpect(jsonPath("$.lignesCommande.length()").value(3))
				.andRespond(withSuccess(navOrder().toString(), MediaType.APPLICATION_JSON));
		ErpSalesOrderResult result = orders.post(order());
		server.verify();
		assertTrue(result.isSuccess(), result.getMessage());
		assertEquals("CV26000003", result.getExternalReference());
		assertEquals("NAV's order CV26000003 of " + NUMBER + ": posted with its 3 lines in one call (deep insert);"
				+ " duplicate check skipped: referenceZS not filterable", result.getMessage());
		assertEquals(3, result.getLines().size());
	}

	@Test
	@DisplayName("Not filterable, then the POST refused: a failure whose message still says the check was skipped")
	void lookupNotFilterableThenRefused() {
		server.expect(requestTo(LOOKUP)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON).body(NOT_FILTERABLE));
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).body("Le client 4110001 est bloqué"));
		ErpSalesOrderResult result = orders.post(order());
		server.verify();
		assertFalse(result.isSuccess());
		assertTrue(result.getMessage().startsWith("zen: posting to the page commandesVente failed: HTTP 400"),
				result.getMessage());
		assertTrue(result.getMessage().endsWith("; duplicate check skipped: referenceZS not filterable"),
				result.getMessage());
	}

	@Test
	@DisplayName("Another lookup refusal still stops the posting: another field not filterable, a 400 about the filter"
			+ " that does not say so, a 500, no answer")
	void lookupOtherErrorsStop() {
		List<ResponseCreator> refusals = Arrays.asList(
				withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"message\":\"Field 'client' is not filterable.\"}}"),
				withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"message\":\"Invalid filter on referenceZS\"}}"),
				withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("The server could not answer"),
				request -> {
					throw new SocketTimeoutException("Read timed out");
				});
		for (ResponseCreator refusal : refusals) {
			server.reset();
			server.expect(requestTo(LOOKUP)).andRespond(refusal);
			ErpSalesOrderResult result = orders.post(order());
			server.verify(); // no POST expected
			assertFalse(result.isSuccess());
			assertTrue(result.getMessage().startsWith("zen: reading the page commandesVente failed"), result.getMessage());
			assertFalse(result.getMessage().contains("duplicate check skipped"), result.getMessage());
		}
	}

	@Test
	@DisplayName("A POST refused for another reason (no fallback), or answered without numero: a failure with the reason")
	void postFailures() {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).body("Le client 4110001 est bloqué"));
		ErpSalesOrderResult refused = orders.post(order());
		server.verify(); // the header alone never posted
		assertFalse(refused.isSuccess());
		assertTrue(refused.getMessage().startsWith("zen: posting to the page commandesVente failed: HTTP 400"),
				refused.getMessage());

		server.reset();
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(ORDERS)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		ErpSalesOrderResult noNumber = orders.post(order());
		assertFalse(noNumber.isSuccess());
		assertEquals("zen: NAV answered the order " + NUMBER + " without its numero", noNumber.getMessage());

		ErpSalesOrderDTO without = order();
		without.setExternalDocumentNo(" ");
		assertFalse(orders.post(without).isSuccess(), "never posted without its number");
	}

	@Test
	@DisplayName("Posted, but its lines cannot be read back: success with numero (the order is in NAV), no lines' answer")
	void linesUnreadable() throws IOException {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(NONE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(ORDERS)).andRespond(withSuccess(header("ok"), MediaType.APPLICATION_JSON));
		server.expect(requestTo(BY_ID)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		ErpSalesOrderResult result = orders.post(order());
		server.verify();
		assertTrue(result.isSuccess());
		assertEquals("CV26000003", result.getExternalReference());
		assertTrue(result.getMessage().contains("its lines could not be read"), result.getMessage());
		assertEquals("ok", result.getOrderMessage());
		assertTrue(result.getLines().isEmpty());
	}

	@Test
	@DisplayName("Only a 4xx naming the nested lines falls back; a 5xx or another 4xx does not")
	void nestedLinesRefused() {
		server.expect(requestTo(ORDERS)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.body("{\"error\":{\"message\":\"The navigation property 'lignesCommande' cannot be used\"}}"));
		server.expect(requestTo(ORDERS)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("lignesCommande"));
		server.expect(requestTo(ORDERS)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("Article inconnu"));
		ZenRestClient client = new ZenRestClient(template, properties);
		assertTrue(ZenStoreOrders.nestedLinesRefused(
				assertThrows(ZenReadException.class, () -> client.postSalesOrder(JSON.createObjectNode()))));
		assertFalse(ZenStoreOrders.nestedLinesRefused(
				assertThrows(ZenReadException.class, () -> client.postSalesOrder(JSON.createObjectNode()))));
		assertFalse(ZenStoreOrders.nestedLinesRefused(
				assertThrows(ZenReadException.class, () -> client.postSalesOrder(JSON.createObjectNode()))));
	}

	// ─── The interceptor and the connector ───────────────────────

	@Test
	@DisplayName("The interceptor: a POST to commandesVente and to the lines of one of its orders only; anything else but"
			+ " GET refused before it leaves")
	void postOnlyToSalesOrders() {
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> template.postForObject(ROOT + "stockPoints", "{}", String.class));
		assertTrue(refused.getMessage().contains("only reads from the ERP (GET)"), refused.getMessage());
		assertThrows(IllegalStateException.class, () -> template.put(ORDERS, "{}"), "a PUT, even there");
		ZenConfig.GetOnly guard = new ZenConfig.GetOnly(ZenOrderFields.SALES_ORDERS_PAGE);
		String company = "/ZEN/api/sat/zsretail/v1.0/companies(x)/";
		assertTrue(guard.postAllowed(company + "commandesVente"));
		assertTrue(guard.postAllowed(company + "commandesVente(" + ID + ")/lignesCommande"));
		assertFalse(guard.postAllowed(company + "commandesVente(" + ID + ")"));
		assertFalse(guard.postAllowed(company + "commandesVente/lignesCommande"));
		assertFalse(guard.postAllowed(company + "infoArticles"));
		assertFalse(new ZenConfig.GetOnly().postAllowed(company + "commandesVente"));
	}

	@Test
	@DisplayName("ZenConnector answers from here; without the orders (the invoices' tests) the defaults: not supported")
	void connector() throws IOException {
		server.expect(requestTo(LOOKUP)).andRespond(withSuccess(foundOurs(), MediaType.APPLICATION_JSON));
		assertEquals("CV26000003", new ZenConnector(null, orders).postSalesOrder(order()).getExternalReference());
		ZenConnector withoutOrders = new ZenConnector(null);
		assertFalse(withoutOrders.postSalesOrder(order()).isSuccess());
		assertThrows(ErpNotSupportedException.class, () -> withoutOrders.fetchVariantStock("70061", "B", "C-0001"));
	}
}
