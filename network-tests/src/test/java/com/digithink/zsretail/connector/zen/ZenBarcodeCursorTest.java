package com.digithink.zsretail.connector.zen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.digithink.zsretail.connector.happyness.NavPosPagesFixture.FakeHeadOffice;
import com.digithink.zsretail.connector.happyness.NavPosPagesFixture.FakeState;
import com.digithink.zsretail.connector.zen.client.ZenCatalogueSource;
import com.digithink.zsretail.connector.zen.client.ZenRestClient;
import com.digithink.zsretail.connector.zen.config.ZenCatalogueSettings;
import com.digithink.zsretail.connector.zen.config.ZenConfig;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.dto.ZenBarcodeRow;
import com.digithink.zsretail.core.erp.engine.CatalogueEngine;
import com.digithink.zsretail.core.erp.engine.CatalogueImport;
import com.digithink.zsretail.core.erp.engine.HeadOfficeCatalogue;
import com.digithink.zsretail.core.erp.engine.StockPoints;
import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Item variants (ZEN), step 12a: the catalogue engine's barcode cursor on ZEN's page barCodes, the real client, mapper
 * and source against a fake page that answers the filters on lastModifiedDateTime as the API does, to the millisecond
 * (no live call; the times are NAV's SQL datetime ticks, .000 .003 .007). The engine is not changed: a page completed
 * to its last time keeps its contract (the rows above the cursor, gt it, an empty answer when caught up), so a time
 * with more rows than a page, NAV's empty date included, is never cut and the guard "rows at or below the cursor"
 * never fires.
 */
class ZenBarcodeCursorTest {

	static final String COMPANY_ID = "1d6c4f00-6ac9-f111-9db8-ad3d0c589c9c";

	private final ObjectMapper json = new ObjectMapper();
	/** The rows of the page barCodes, as NAV keeps them. */
	private final List<ZenBarcodeRow> page = new ArrayList<>();
	/** The $filter of each call, decoded (null: none). */
	private final List<String> filters = new ArrayList<>();
	private FakeHeadOffice headOffice;
	private FakeState state;
	private CatalogueEngine engine;

	@BeforeEach
	void setUp() {
		ZenProperties properties = new ZenProperties();
		properties.setBaseUrl("http://nav.test:7048/BC280");
		properties.setCompanyId(COMPANY_ID);
		properties.setUsername("user");
		properties.setPassword("secret");
		properties.setDefaultVat(19);
		properties.setBarcodePageSize(3);
		properties.setPacketSize(2);
		RestTemplate template = ZenConfig.restTemplate(properties);
		MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
		server.expect(ExpectedCount.manyTimes(), request -> {
		}).andRespond(this::answer);

		headOffice = new FakeHeadOffice();
		state = new FakeState();
		state.put("items.run", "true");
		state.put("items.pending", "0");
		for (int n = 1; n <= 10; n++) {
			String code = "70061~" + variant(n);
			headOffice.items.put(code,
					new HeadOfficeCatalogue.Item(code, code, "", 10.0, 19, true, code, "", null, null, null));
		}
		engine = new CatalogueEngine(new ZenCatalogueSource(new ZenRestClient(template, properties), properties),
				headOffice, new NoStockPoints(), state, new ZenCatalogueSettings(properties), new CatalogueImport() {
					@Override
					public void items(List<ErpItemDTO> packet) {
						headOffice.importItems(packet);
					}

					@Override
					public void barcodes(List<ErpItemBarcodeDTO> packet) {
						headOffice.importBarcodes(packet);
					}
				});

		// Four rows of NAV's empty date and four of one time: each more than a page of 3
		for (int n = 1; n <= 4; n++) {
			page.add(row(n, "0001-01-01T00:00:00Z"));
		}
		page.add(row(5, "2026-10-11T16:53:37.147Z"));
		page.add(row(6, "2026-10-11T16:53:37.15Z"));
		page.add(row(7, "2026-10-11T16:53:37.150Z"));
		page.add(row(8, "2026-10-11T17:53:37.15+01:00"));
		page.add(row(9, "2026-10-11T16:53:37.15Z"));
		page.add(row(10, "2026-10-11T16:53:38Z"));
	}

	@Test
	@DisplayName("A first run reads every row, a time bigger than a page never cut, caught up, no guard; the cursor is"
			+ " the last row's lastModifiedDateTime in epoch milliseconds")
	void firstRun() {
		CatalogueRun<ErpItemBarcodeDTO> run = engine.barcodes();

		assertEquals(Boolean.TRUE, run.getSummary().get("caughtUp"));
		assertNull(run.getSummary().get("guard"));
		assertEquals(10, run.count("applied"));
		assertEquals(10, headOffice.barcodes.size());
		assertEquals(4, run.count("pagesRead"), "3 pages and the empty answer");
		assertEquals(Arrays.asList(null, "lastModifiedDateTime lt 1970-01-01T00:00:00Z",
				"lastModifiedDateTime gt 1970-01-01T00:00:00.001Z", "lastModifiedDateTime eq 2026-10-11T16:53:37.150Z",
				"lastModifiedDateTime gt 2026-10-11T16:53:37.150Z", "lastModifiedDateTime gt 2026-10-11T16:53:38Z"),
				filters, "each full page completed to its last time");
		assertEquals(String.valueOf(Instant.parse("2026-10-11T16:53:38Z").toEpochMilli()), state.get("barcodes.cursor"));
	}

	@Test
	@DisplayName("Nothing new: one call, caught up, no guard; a barcode moved to another variant later is read again and"
			+ " moved")
	void nextRuns() {
		engine.barcodes();
		filters.clear();

		CatalogueRun<ErpItemBarcodeDTO> idle = engine.barcodes();
		assertEquals(Boolean.TRUE, idle.getSummary().get("caughtUp"));
		assertNull(idle.getSummary().get("guard"));
		assertEquals(1, idle.count("pagesRead"));
		assertEquals(0, idle.count("applied"));
		assertEquals(Collections.singletonList("lastModifiedDateTime gt 2026-10-11T16:53:38Z"), filters);

		page.set(9, new ZenBarcodeRow("id10", "70061", "001", barcode(10), "2026-10-11T16:54:00Z"));
		CatalogueRun<ErpItemBarcodeDTO> moved = engine.barcodes();

		assertEquals(Boolean.TRUE, moved.getSummary().get("caughtUp"));
		assertEquals(1, moved.count("applied"));
		assertEquals("70061~001", headOffice.barcodes.get(barcode(10)).itemCode);
		assertEquals(String.valueOf(Instant.parse("2026-10-11T16:54:00Z").toEpochMilli()), state.get("barcodes.cursor"));
	}

	// ─── The fake page barCodes ─────────────────────────────────

	/**
	 * $filter on lastModifiedDateTime (gt, ge, eq, lt, and), to the millisecond; by lastModifiedDateTime (NAV's order
	 * kept for a tie), $top.
	 */
	private ClientHttpResponse answer(ClientHttpRequest request) throws IOException {
		MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
		String filter = query.getFirst("$filter") == null ? null
				: UriUtils.decode(query.getFirst("$filter"), StandardCharsets.UTF_8);
		filters.add(filter);
		long top = query.getFirst("$top") == null ? Long.MAX_VALUE : Long.parseLong(query.getFirst("$top"));
		List<ZenBarcodeRow> answered = page.stream().filter(row -> matches(row, filter))
				.sorted(Comparator.comparing(ZenBarcodeCursorTest::modified)).limit(top).collect(Collectors.toList());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("value", answered);
		return withSuccess(json.writeValueAsString(body), MediaType.APPLICATION_JSON).createResponse(request);
	}

	private static boolean matches(ZenBarcodeRow row, String filter) {
		if (filter == null) {
			return true;
		}
		for (String condition : filter.split(" and ")) {
			String[] parts = condition.split(" ");
			if (parts.length != 3 || !parts[0].equals("lastModifiedDateTime")) {
				throw new AssertionError("a filter the fake page does not answer: " + filter);
			}
			int compared = modified(row).compareTo(Instant.parse(parts[2]).truncatedTo(ChronoUnit.MILLIS));
			if (!kept(parts[1], compared, filter)) {
				return false;
			}
		}
		return true;
	}

	private static boolean kept(String operator, int compared, String filter) {
		switch (operator) {
		case "gt":
			return compared > 0;
		case "ge":
			return compared >= 0;
		case "eq":
			return compared == 0;
		case "lt":
			return compared < 0;
		default:
			throw new AssertionError("an operator the fake page does not answer: " + filter);
		}
	}

	private static Instant modified(ZenBarcodeRow row) {
		return OffsetDateTime.parse(row.getLastModifiedDateTime()).toInstant().truncatedTo(ChronoUnit.MILLIS);
	}

	private static ZenBarcodeRow row(int n, String modified) {
		return new ZenBarcodeRow("id" + n, "70061", variant(n), barcode(n), modified);
	}

	private static String variant(int n) {
		return String.format("%03d", n);
	}

	private static String barcode(int n) {
		return String.format("90000027405%02d", n);
	}

	/** No point de stock: the barcode run reads none. */
	private static final class NoStockPoints implements StockPoints {
		@Override
		public List<Point> activePoints() {
			return new ArrayList<>();
		}

		@Override
		public Map<String, Row> rows(long pointId) {
			return new LinkedHashMap<>();
		}

		@Override
		public int write(long pointId, List<Row> packet) {
			return 0;
		}

		@Override
		public void recordRead(long pointId, String status, String summary) {
		}
	}
}
