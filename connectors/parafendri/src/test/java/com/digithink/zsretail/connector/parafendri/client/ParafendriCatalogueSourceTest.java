package com.digithink.zsretail.connector.parafendri.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.connector.parafendri.config.ParafendriConfig;
import com.digithink.zsretail.connector.parafendri.config.ParafendriProperties;
import com.digithink.zsretail.connector.parafendri.mapper.ParafendriMapper;
import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource.Part;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * Head office catalogue: ParafendriCatalogueSource against a mock server, with the samples of the store's steps 2 to 4.
 * The tree (four levels), the items of one point de stock whole and from its checkpoint (ge), the barcodes by the
 * engine's cursor (ZEN's contract: epoch milliseconds, gt, orderby, $top; a full page completed with the rows of its
 * last millisecond, a row once by its GUID id; every row's position, left out ones included) and by item.
 */
class ParafendriCatalogueSourceTest {

	static final String COMPANY = "http://bc.test:7048/BC280/api/sat/zsretail/v1.0/companies("
			+ "6b29fc40-ca47-1067-b31d-00dd010662da)/";
	static final String TREE = COMPANY + "itemCategories";
	static final String STOCK_POINTS = COMPANY + "stockPoints?$filter=locationCode%20eq%20'PV-GREMDA'";
	static final String STOCK_POINTS_SINCE = STOCK_POINTS + "%20and%20lastModifiedDateTime%20ge%202026-10-25T08:15:42.317Z";
	static final String BARCODES = COMPANY + "barCodes";
	static final String FIRST_PAGE = BARCODES + "?$orderby=lastModifiedDateTime&$top=1000";
	static final String AFTER_117 = BARCODES
			+ "?$filter=lastModifiedDateTime%20gt%202026-10-25T09:00:00.117Z&$orderby=lastModifiedDateTime&$top=1000";

	private MockRestServiceServer server;
	private ParafendriCatalogueSource source;

	@BeforeEach
	void setUp() {
		ParafendriProperties properties = new ParafendriProperties();
		properties.setBaseUrl("http://bc.test:7048/BC280");
		properties.setCompanyId("6b29fc40-ca47-1067-b31d-00dd010662da");
		properties.setUsername("user");
		properties.setPassword("secret");
		RestTemplate template = ParafendriConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		source = new ParafendriCatalogueSource(new ParafendriRestClient(template, properties));
	}

	private static String sample(String name) throws IOException {
		try (InputStream in = ParafendriCatalogueSourceTest.class.getResourceAsStream("/parafendri/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("The ability HEAD_OFFICE_STOCK_POINTS declared (beside WHOLE_TICKET_EXPORT); changes only, no images; the"
			+ " page labels and addresses")
	void declared() {
		assertEquals(java.util.EnumSet.of(ErpAbility.WHOLE_TICKET_EXPORT, ErpAbility.HEAD_OFFICE_STOCK_POINTS),
				new ParafendriConnectorDescriptor().abilities());
		assertTrue(source.readsChangesOnly());
		assertFalse(source.readsItemImages());
		assertEquals("itemCategories", source.pageLabel(Part.CATEGORIES));
		assertEquals("stockPoints", source.pageLabel(Part.ITEMS));
		assertEquals("barCodes", source.pageLabel(Part.BARCODES));
		assertEquals(BARCODES, source.pageUrl(Part.BARCODES));
	}

	@Test
	@DisplayName("categories(): the tree itemCategories in its four levels, as the store's step 2")
	void categories() throws IOException {
		server.expect(requestTo(TREE)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(sample("itemCategories.json"), MediaType.APPLICATION_JSON));
		ErpCategories tree = source.categories();
		server.verify();
		assertTrue(tree.getCategories().getRows().stream().anyMatch(c -> "COS".equals(c.getCode())));
		assertTrue(tree.getFamilies().getRows().stream().anyMatch(f -> "COS-COF".equals(f.getCode())));
		assertTrue(tree.getSubFamilies().getRows().stream().anyMatch(s -> "COS-COF-PAR".equals(s.getCode())));
		assertFalse(tree.getSections().getRows().isEmpty(), "the section of the sample");
	}

	@Test
	@DisplayName("items(PV-GREMDA): the tree, then every row of the point de stock (the other location's left out),"
			+ " prices before VAT as received, blocked rows inactive")
	void itemsOfOnePoint() throws IOException {
		server.expect(requestTo(TREE)).andRespond(withSuccess(sample("itemCategories.json"), MediaType.APPLICATION_JSON));
		server.expect(requestTo(STOCK_POINTS))
				.andRespond(withSuccess(sample("stockPoints.json"), MediaType.APPLICATION_JSON));
		ErpRead<ErpItemDTO> read = source.items("PV-GREMDA");
		server.verify();
		assertEquals(13, read.getRead());
		assertEquals(9, read.getKept());
		ErpItemDTO first = read.getRows().get(0);
		assertEquals("ITM001", first.getCode());
		assertEquals(new java.math.BigDecimal("18.90756"), first.getUnitPrice(), "never rounded");
		assertTrue(read.getRows().stream().anyMatch(item -> Boolean.FALSE.equals(item.getActive())), "a blocked row");
	}

	@Test
	@DisplayName("items(PV-GREMDA, since): the rows modified at or after the checkpoint (ge, in UTC)")
	void itemsFromCheckpoint() throws IOException {
		server.expect(requestTo(TREE)).andRespond(withSuccess(sample("itemCategories.json"), MediaType.APPLICATION_JSON));
		server.expect(requestTo(STOCK_POINTS_SINCE)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		ErpRead<ErpItemDTO> read = source.items("PV-GREMDA", OffsetDateTime.parse("2026-10-25T09:15:42.317+01:00"));
		server.verify();
		assertEquals(0, read.getRead());
	}

	@Test
	@DisplayName("barcodesAfter(0): the first page by lastModifiedDateTime, no filter; every row's position (epoch ms,"
			+ " the empty date 1), the left-out rows' too; the external id the GUID id")
	void barcodesFromTheStart() throws IOException {
		server.expect(requestTo(FIRST_PAGE)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(sample("barCodes.json"), MediaType.APPLICATION_JSON));
		ErpRead<ErpBarcodeRow> read = source.barcodesAfter(0);
		server.verify();
		assertEquals(7, read.getRead());
		assertEquals(7, read.getPositions().size(), "left out rows count for the cursor");
		assertEquals(Long.valueOf(OffsetDateTime.parse("2026-10-25T09:00:00.100Z").toInstant().toEpochMilli()),
				read.getPositions().get(0));
		assertTrue(read.getPositions().contains(ParafendriMapper.EMPTY_DATE_POSITION), "0001-01-01");
		assertEquals(Arrays.asList("6191234500011", "6191234500028", "6191234500035", "6191234500059", "6191234500066"),
				read.getRows().stream().map(row -> row.getBarcode().getBarcode()).collect(Collectors.toList()));
		assertEquals("b1c2d3e4-0001-f111-9a06-00155d66bd01", read.getRows().get(0).getBarcode().getExternalId());
		assertEquals(Integer.valueOf(1), read.getLeftOut().get(ParafendriMapper.BLANK_BARCODE));
	}

	@Test
	@DisplayName("barcodesAfter(position): gt its instant; an empty answer (0 rows read) is the engine's caught up")
	void barcodesAfterCursor() {
		long cursor = OffsetDateTime.parse("2026-10-25T09:00:00.117Z").toInstant().toEpochMilli();
		server.expect(requestTo(AFTER_117)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		ErpRead<ErpBarcodeRow> read = source.barcodesAfter(cursor);
		server.verify();
		assertEquals(0, read.getRead());
	}

	@Test
	@DisplayName("A full page (1000 rows) ending inside a millisecond: every row of that millisecond read too (eq), a row"
			+ " already on the page once (its GUID id)")
	void fullPageCompleted() {
		StringBuilder page = new StringBuilder("{\"value\":[");
		for (int i = 0; i < 1000; i++) {
			String at = i < 998 ? "2026-11-01T10:00:00.5" + String.format("%02d", i % 100) + "Z"
					: "2026-11-01T10:00:00.999Z";
			page.append(i == 0 ? "" : ",").append(row("id-" + i, "ITM" + i, "619" + i, at));
		}
		page.append("]}");
		String sameMillisecond = "{\"value\":[" + row("id-998", "ITM998", "619998", "2026-11-01T10:00:00.999Z") + ","
				+ row("id-999", "ITM999", "619999", "2026-11-01T10:00:00.999Z") + ","
				+ row("id-1000", "ITM1000", "6191000", "2026-11-01T10:00:00.999Z") + "]}";
		server.expect(requestTo(FIRST_PAGE)).andRespond(withSuccess(page.toString(), MediaType.APPLICATION_JSON));
		server.expect(requestTo(BARCODES + "?$filter=lastModifiedDateTime%20eq%202026-11-01T10:00:00.999Z"))
				.andRespond(withSuccess(sameMillisecond, MediaType.APPLICATION_JSON));
		ErpRead<ErpBarcodeRow> read = source.barcodesAfter(0);
		server.verify();
		assertEquals(1001, read.getRead(), "the 1000 of the page and the one more of its last millisecond");
		assertEquals("6191000", read.getRows().get(1000).getBarcode().getBarcode());
	}

	@Test
	@DisplayName("barcodesOfItems: the barcodes of these items (itemNo eq ... or ...), another item's row left out")
	void barcodesOfItems() {
		server.expect(requestTo(BARCODES + "?$filter=itemNo%20eq%20'ITM001'%20or%20itemNo%20eq%20'ITM002'"))
				.andRespond(withSuccess("{\"value\":[" + row("a", "ITM001", "111", "2026-10-25T09:00:00.100Z") + ","
						+ row("b", "ITM777", "777", "2026-10-25T09:00:00.101Z") + "]}", MediaType.APPLICATION_JSON));
		ErpRead<ErpBarcodeRow> read = source.barcodesOfItems(Arrays.asList("ITM001", "ITM002"));
		server.verify();
		List<String> kept = read.getRows().stream().map(row -> row.getBarcode().getBarcode()).collect(Collectors.toList());
		assertEquals(Arrays.asList("111"), kept);
		assertEquals(2, read.getRead());
		assertEquals(2, read.getPositions().size());
	}

	@Test
	@DisplayName("The filters: gt after a position, none from the start; eq for one millisecond, lt 1970 for the empty date")
	void filters() {
		assertEquals(null, ParafendriRestClient.barcodesAfterFilter(0));
		assertEquals("lastModifiedDateTime gt 2026-10-25T09:00:00.117Z", ParafendriRestClient
				.barcodesAfterFilter(OffsetDateTime.parse("2026-10-25T09:00:00.117Z").toInstant().toEpochMilli()));
		assertEquals("lastModifiedDateTime lt 1970-01-01T00:00:00Z",
				ParafendriRestClient.barcodesAtFilter(ParafendriMapper.EMPTY_DATE_POSITION));
		assertEquals(ParafendriMapper.EMPTY_DATE_POSITION, ParafendriMapper.position("0001-01-01T00:00:00Z"));
		assertEquals(ParafendriMapper.EMPTY_DATE_POSITION, ParafendriMapper.position(" "));
	}

	private static String row(String id, String itemNo, String barCode, String at) {
		return "{\"id\":\"" + id + "\",\"itemNo\":\"" + itemNo + "\",\"barCode\":\"" + barCode
				+ "\",\"lastModifiedDateTime\":\"" + at + "\"}";
	}
}
