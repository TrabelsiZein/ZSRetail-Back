package com.digithink.zsretail.connector.parafendri.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.connector.parafendri.config.ParafendriConfig;
import com.digithink.zsretail.connector.parafendri.config.ParafendriProperties;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpSyncFilter;

/**
 * ParaFendri, step 4: fetchItemBarcodes against a mock server. barCodes is company-wide: every row on a first run (no
 * checkpoint); from the job's checkpoint the rows changed since then (ge), plus the barcodes of the items this run's
 * items part got from its own checkpoint, so that an item added to the point de vente later has its barcode although its
 * barcode row did not change; a row read twice kept once; the run's items used once; the log without the rows.
 */
class ParafendriBarcodesConnectorTest {

	static final String COMPANY = "http://bc.test:7048/BC280/api/sat/zsretail/v1.0/companies("
			+ ParafendriConnectorTest.COMPANY_ID + ")/";
	static final String TREE = COMPANY + "itemCategories";
	static final OffsetDateTime ITEMS_SINCE = OffsetDateTime.parse("2026-10-25T08:15:42.317Z");
	static final OffsetDateTime BARCODES_SINCE = OffsetDateTime.parse("2026-10-25T09:00:00.117Z");
	static final String STOCK_POINTS_SINCE = COMPANY + "stockPoints?$filter=locationCode%20eq%20'PV-GREMDA'"
			+ "%20and%20lastModifiedDateTime%20ge%202026-10-25T08:15:42.317Z";
	static final String BARCODES = COMPANY + "barCodes";
	static final String BARCODES_DELTA = BARCODES + "?$filter=lastModifiedDateTime%20ge%202026-10-25T09:00:00.117Z";
	static final String BARCODES_OF_ITM050 = BARCODES + "?$filter=itemNo%20eq%20'ITM050'";

	/** ITM050 newly at PV-GREMDA (its stockPoints row new); its barcode row is from before. */
	static final String NEW_ITEM = "{\"value\":[{\"locationCode\":\"PV-GREMDA\",\"itemNo\":\"ITM050\","
			+ "\"stockPointDescription\":\"Ajouté plus tard\",\"unitPrice\":10,\"vatPercent\":19,"
			+ "\"lastModifiedDateTime\":\"2026-10-30T10:00:00.000Z\"}]}";
	static final String ITM050_BARCODE = "{\"value\":[{\"id\":\"b1c2d3e4-0050-f111-9a06-00155d66bd01\","
			+ "\"itemNo\":\"ITM050\",\"barCode\":\"6191234500509\",\"lastModifiedDateTime\":\"2026-10-01T07:00:00.000Z\"}]}";

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

	@AfterEach
	void clear() {
		connector.clearLastPullOperationResult();
		ParafendriConnector.clearItemsOfThisRun(); // the thread is the next test's
	}

	private static ErpSyncFilter since(OffsetDateTime time) {
		ErpSyncFilter filter = new ErpSyncFilter();
		filter.setUpdatedAfter(time);
		return filter;
	}

	private static String sample(String name) throws IOException {
		try (InputStream in = ParafendriBarcodesConnectorTest.class.getResourceAsStream("/parafendri/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * The requests of the items part of a run from its checkpoint, the tree then stockPoints which gives ITM050 (every
	 * expectation is declared before the first request: the mock server refuses one after).
	 */
	private void expectItemsPartGivingItm050() throws IOException {
		server.expect(requestTo(TREE)).andRespond(withSuccess(sample("itemCategories.json"), MediaType.APPLICATION_JSON));
		server.expect(requestTo(STOCK_POINTS_SINCE)).andRespond(withSuccess(NEW_ITEM, MediaType.APPLICATION_JSON));
	}

	/** The items part of a run from its checkpoint: ITM050, new at the point de vente. */
	private void runItemsPart() {
		assertEquals(1, connector.fetchItems(since(ITEMS_SINCE)).size());
	}

	private static String values(List<ErpItemBarcodeDTO> barcodes) {
		return barcodes.stream().map(b -> b.getBarcode() + " " + b.getItemExternalId()).collect(Collectors.joining(", "));
	}

	@Test
	@DisplayName("First run (no checkpoint): every row of barCodes, one GET, no read per item")
	void firstRun() throws IOException {
		expectItemsPartGivingItm050();
		server.expect(requestTo(BARCODES)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(sample("barCodes.json"), MediaType.APPLICATION_JSON));
		runItemsPart();
		List<ErpItemBarcodeDTO> barcodes = connector.fetchItemBarcodes(new ErpSyncFilter());
		server.verify();
		assertEquals(5, barcodes.size());
	}

	@Test
	@DisplayName("An item added later: its barcode row did not change, the delta misses it, the read for the run's items gets it")
	@SuppressWarnings("unchecked")
	void itemAddedLater() throws IOException {
		expectItemsPartGivingItm050();
		server.expect(requestTo(BARCODES_DELTA)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BARCODES_OF_ITM050))
				.andRespond(withSuccess(ITM050_BARCODE, MediaType.APPLICATION_JSON));
		runItemsPart();
		List<ErpItemBarcodeDTO> barcodes = connector.fetchItemBarcodes(since(BARCODES_SINCE));
		server.verify();
		assertEquals("6191234500509 ITM050", values(barcodes));
		Map<String, Object> summary = (Map<String, Object>) connector.getLastPullOperationResult().getRawResponse();
		assertEquals(0, summary.get("readFromCheckpoint"));
		assertEquals(1, summary.get("itemsAsked"));
		assertEquals(1, summary.get("readForItems"));
		assertEquals(BARCODES_DELTA, connector.getLastPullOperationResult().getUrl());
		assertFalse(summary.containsKey("rows"));
	}

	@Test
	@DisplayName("The same row in the delta and for an item: kept once, counted")
	@SuppressWarnings("unchecked")
	void sameRowTwice() throws IOException {
		expectItemsPartGivingItm050();
		server.expect(requestTo(BARCODES_DELTA)).andRespond(withSuccess(ITM050_BARCODE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(BARCODES_OF_ITM050))
				.andRespond(withSuccess(ITM050_BARCODE, MediaType.APPLICATION_JSON));
		runItemsPart();
		assertEquals("6191234500509 ITM050", values(connector.fetchItemBarcodes(since(BARCODES_SINCE))));
		Map<String, Object> summary = (Map<String, Object>) connector.getLastPullOperationResult().getRawResponse();
		assertEquals(1, summary.get("sameRowTwice"));
		server.verify();
	}

	@Test
	@DisplayName("The run's items used once: a second barcodes read reads the delta only")
	void runItemsUsedOnce() throws IOException {
		expectItemsPartGivingItm050();
		server.expect(requestTo(BARCODES_DELTA)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BARCODES_OF_ITM050))
				.andRespond(withSuccess(ITM050_BARCODE, MediaType.APPLICATION_JSON));
		server.expect(requestTo(BARCODES_DELTA)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		runItemsPart();
		assertEquals(1, connector.fetchItemBarcodes(since(BARCODES_SINCE)).size());
		assertEquals(0, connector.fetchItemBarcodes(since(BARCODES_SINCE)).size());
		server.verify();
	}

	@Test
	@DisplayName("Items read whole (no items checkpoint): no read per item afterwards, the barcodes delta only")
	void itemsReadWhole() throws IOException {
		server.expect(requestTo(TREE)).andRespond(withSuccess(sample("itemCategories.json"), MediaType.APPLICATION_JSON));
		server.expect(requestTo(COMPANY + "stockPoints?$filter=locationCode%20eq%20'PV-GREMDA'"))
				.andRespond(withSuccess(NEW_ITEM, MediaType.APPLICATION_JSON));
		server.expect(requestTo(BARCODES_DELTA)).andRespond(withSuccess(ITM050_BARCODE, MediaType.APPLICATION_JSON));
		connector.fetchItems(new ErpSyncFilter());
		assertEquals(1, connector.fetchItemBarcodes(since(BARCODES_SINCE)).size());
		server.verify();
	}
}
