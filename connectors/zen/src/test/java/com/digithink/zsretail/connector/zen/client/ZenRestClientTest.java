package com.digithink.zsretail.connector.zen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.zen.config.ZenConfig;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.dto.ZenBarcodeRow;
import com.digithink.zsretail.connector.zen.dto.ZenStockPointRow;
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;

/**
 * Item variants (ZEN), step 8: the addresses and filters of the reads of ZEN's API, against a mock server (no live
 * call): the rows of a location from a checkpoint (lastModifiedDateTime ge, in UTC), the next links followed, the
 * barcodes of the page barCodes by lastModifiedDateTime (step 12a, a full page completed to its last millisecond) and by
 * item and variant, a page given as a whole address, GET only.
 */
class ZenRestClientTest {

	static final String COMPANY_ID = "89c8b192-3f2e-f111-9f24-7ced8db3f120";
	static final String ROOT = "http://nav.test:7158/ZEN/api/sat/zsretail/v1.0/companies(" + COMPANY_ID + ")/";

	private ZenProperties properties;
	private RestTemplate template;
	private MockRestServiceServer server;
	private ZenRestClient client;

	@BeforeEach
	void setUp() {
		properties = new ZenProperties();
		properties.setBaseUrl("http://nav.test:7158/ZEN");
		properties.setCompany("ZEN");
		properties.setCompanyId(COMPANY_ID); // no lookup: see companyByName
		properties.setUsername("user");
		properties.setPassword("secret");
		properties.setDefaultVat(19);
		template = ZenConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		client = new ZenRestClient(template, properties);
	}

	@Test
	@DisplayName("The $filter of a location: by its code (a quote doubled), and from a checkpoint ge its time in UTC")
	void stockPointsFilter() {
		assertEquals("locationCode eq 'ARGENTE'", ZenRestClient.stockPointsFilter("ARGENTE", null));
		assertEquals("locationCode eq 'O''NEIL'", ZenRestClient.stockPointsFilter(" O'NEIL ", null));
		assertEquals("locationCode eq 'ARGENTE' and lastModifiedDateTime ge 2026-10-11T16:53:37.15Z",
				ZenRestClient.stockPointsFilter("ARGENTE", OffsetDateTime.parse("2026-10-11T16:53:37.150Z")));
		assertEquals("locationCode eq 'ARGENTE' and lastModifiedDateTime ge 2026-10-11T16:53:37.15Z",
				ZenRestClient.stockPointsFilter("ARGENTE", OffsetDateTime.parse("2026-10-11T17:53:37.15+01:00")),
				"no + in the address: the time in UTC");
	}

	@Test
	@DisplayName("The rows of a location from a checkpoint: the filter in the address, the next link followed")
	void readStockPoints() {
		String next = ROOT + "stockPoints?$skiptoken=2";
		server.expect(requestTo(ROOT + "stockPoints?$filter=locationCode%20eq%20'FRANCHISE'%20and%20lastModifiedDateTime"
				+ "%20ge%202026-10-11T16:53:37.15Z")).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"value\":[{\"locationCode\":\"FRANCHISE\",\"itemNo\":\"14ZDU05\",\"variantCode\":"
						+ "\"001\",\"publicPrice\":19.5,\"clearance\":true,\"clearancePrice\":7}],\"@odata.nextLink\":\"" + next
						+ "\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(next)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
				"{\"value\":[{\"locationCode\":\"FRANCHISE\",\"itemNo\":\"14ZDU05\",\"variantCode\":\"002\"}]}",
				MediaType.APPLICATION_JSON));

		List<ZenStockPointRow> rows = client.readStockPoints("FRANCHISE",
				OffsetDateTime.parse("2026-10-11T16:53:37.15Z"));

		server.verify();
		assertEquals(2, rows.size());
		assertEquals("002", rows.get(1).getVariantCode());
		assertTrue(rows.get(0).getClearance());
	}

	@Test
	@DisplayName("A whole read (no checkpoint): the location only; the tree without a filter")
	void wholeRead() {
		server.expect(requestTo(ROOT + "stockPoints?$filter=locationCode%20eq%20'ARGENTE'"))
				.andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(ROOT + "itemCategories")).andRespond(withSuccess(
				"{\"value\":[{\"code\":\"VET\",\"description\":\"Vetements\",\"parentCategory\":\"\",\"indentation\":0}]}",
				MediaType.APPLICATION_JSON));

		assertTrue(client.readStockPoints("ARGENTE", null).isEmpty());
		assertEquals("VET", client.readCategories().get(0).getCode());
		server.verify();
	}

	@Test
	@DisplayName("Step 12a: the barcodes' filters: above a position, its next millisecond in UTC (none at 0); the rows of"
			+ " one millisecond; those of the empty date, every row before 1970-01-01T00:00:00.002Z")
	void barcodeFilters() {
		long at = Instant.parse("2026-10-11T16:53:37.150Z").toEpochMilli();
		assertNull(ZenRestClient.barcodesAfterFilter(0), "nothing read yet: every row");
		assertEquals("lastModifiedDateTime ge 2026-10-11T16:53:37.151Z", ZenRestClient.barcodesAfterFilter(at));
		assertEquals("lastModifiedDateTime ge 1970-01-01T00:00:00.002Z",
				ZenRestClient.barcodesAfterFilter(ZenMapper.EMPTY_DATE_POSITION));
		assertEquals("lastModifiedDateTime ge 2026-10-11T16:53:37.150Z and lastModifiedDateTime lt 2026-10-11T16:53:37.151Z",
				ZenRestClient.barcodesAtFilter(at));
		assertEquals("lastModifiedDateTime lt 1970-01-01T00:00:00.002Z",
				ZenRestClient.barcodesAtFilter(ZenMapper.EMPTY_DATE_POSITION));
	}

	@Test
	@DisplayName("Step 12a: barCodes from 0: no filter, by lastModifiedDateTime, $top, $select of ZEN's names; a page"
			+ " not full is the whole answer")
	void barcodesFromTheStart() {
		server.expect(requestTo(ROOT + "barCodes?$orderby=lastModifiedDateTime&$top=1000"
				+ "&$select=id,itemNo,variantCode,barCode,lastModifiedDateTime")).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"value\":[" + barcode("a1", "70061", "001", "9000002740592",
						"0001-01-01T00:00:00Z") + "]}", MediaType.APPLICATION_JSON));

		List<ZenBarcodeRow> page = client.readBarcodesAfter(0);

		server.verify();
		assertEquals(1, page.size());
		assertEquals("a1", page.get(0).getId());
		assertEquals("70061", page.get(0).getItemNo());
		assertEquals("001", page.get(0).getVariantCode());
		assertEquals("9000002740592", page.get(0).getBarCode());
		assertEquals("0001-01-01T00:00:00Z", page.get(0).getLastModifiedDateTime());
	}

	@Test
	@DisplayName("Step 12a: a full page is completed with every row of its last millisecond (next links followed),"
			+ " a row already on the page once")
	void fullPageCompletedToItsLastMillisecond() {
		properties.setBarcodePageSize(2);
		long cursor = Instant.parse("2026-10-11T16:53:37.150Z").toEpochMilli();
		String next = ROOT + "barCodes?$skiptoken=2";
		server.expect(requestTo(ROOT + "barCodes?$filter=lastModifiedDateTime%20ge%202026-10-11T16:53:37.151Z"
				+ "&$orderby=lastModifiedDateTime&$top=2&$select=id,itemNo,variantCode,barCode,lastModifiedDateTime"))
				.andRespond(withSuccess("{\"value\":[" + barcode("b1", "70061", "001", "1", "2026-10-11T16:53:37.151Z")
						+ "," + barcode("b2", "70061", "002", "2", "2026-10-11T17:53:38.2+01:00") + "]}",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(ROOT + "barCodes?$filter=lastModifiedDateTime%20ge%202026-10-11T16:53:38.200Z"
				+ "%20and%20lastModifiedDateTime%20lt%202026-10-11T16:53:38.201Z"
				+ "&$select=id,itemNo,variantCode,barCode,lastModifiedDateTime"))
				.andRespond(withSuccess("{\"value\":[" + barcode("b2", "70061", "002", "2", "2026-10-11T16:53:38.2Z")
						+ "," + barcode("b3", "70061", "003", "3", "2026-10-11T16:53:38.2Z") + "],\"@odata.nextLink\":\""
						+ next + "\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(next)).andRespond(withSuccess("{\"value\":["
				+ barcode("b4", "70061", "004", "4", "2026-10-11T16:53:38.2004Z") + "]}", MediaType.APPLICATION_JSON));

		List<ZenBarcodeRow> page = client.readBarcodesAfter(cursor);

		server.verify();
		assertEquals(Arrays.asList("b1", "b2", "b3", "b4"),
				page.stream().map(ZenBarcodeRow::getId).collect(Collectors.toList()));
	}

	@Test
	@DisplayName("Step 12a: a full page of NAV's empty date is completed with every row before 1970")
	void fullPageOfEmptyDates() {
		properties.setBarcodePageSize(1);
		server.expect(requestTo(ROOT + "barCodes?$orderby=lastModifiedDateTime&$top=1"
				+ "&$select=id,itemNo,variantCode,barCode,lastModifiedDateTime")).andRespond(withSuccess(
						"{\"value\":[" + barcode("e1", "1000", "", "1", "0001-01-01T00:00:00Z") + "]}",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(ROOT + "barCodes?$filter=lastModifiedDateTime%20lt%201970-01-01T00:00:00.002Z"
				+ "&$select=id,itemNo,variantCode,barCode,lastModifiedDateTime")).andRespond(withSuccess(
						"{\"value\":[" + barcode("e1", "1000", "", "1", "0001-01-01T00:00:00Z") + ","
								+ barcode("e2", "1001", "", "2", "0001-01-01T00:00:00Z") + "]}",
						MediaType.APPLICATION_JSON));

		assertEquals(2, client.readBarcodesAfter(0).size());
		server.verify();
	}

	@Test
	@DisplayName("Step 12a: the barcodes of some items by itemNo and variantCode, every page")
	void barcodesOfItems() {
		server.expect(requestTo(ROOT + "barCodes?$filter=(itemNo%20eq%20'70061'%20and%20variantCode%20eq%20'001')"
				+ "%20or%20(itemNo%20eq%20'1000'%20and%20variantCode%20eq%20'')"
				+ "&$select=id,itemNo,variantCode,barCode,lastModifiedDateTime"))
				.andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

		assertTrue(client.readBarcodesOfItems(Arrays.asList("70061~001", "1000")).isEmpty());
		server.verify();
		assertEquals("(itemNo eq '70061' and variantCode eq '001') or (itemNo eq 'O''NEIL' and variantCode eq"
				+ " 'LS-10PC-B')", ZenRestClient.barcodesOfItemsFilter(Arrays.asList("70061~001", "O'NEIL~LS-10PC-B")));
	}

	private static String barcode(String id, String itemNo, String variantCode, String barCode, String modified) {
		return "{\"id\":\"" + id + "\",\"itemNo\":\"" + itemNo + "\",\"variantCode\":\"" + variantCode
				+ "\",\"unitOfMeasureCode\":\"PCS\",\"barCode\":\"" + barCode + "\",\"description\":\"\","
				+ "\"lastModifiedDateTime\":\"" + modified + "\"}";
	}

	@Test
	@DisplayName("A page given as a whole address is read there; the address of a page for the log")
	void wholeAddress() {
		properties.getPage().setBarcodes("http://nav.test:7048/ZEN/ODataV4/Company('ZEN')/ItemBarCodePOS");

		assertEquals("http://nav.test:7048/ZEN/ODataV4/Company('ZEN')/ItemBarCodePOS", client.pageUrl("http://nav.test:7048"
				+ "/ZEN/ODataV4/Company('ZEN')/ItemBarCodePOS"));
		assertEquals(ROOT + "stockPoints", client.pageUrl("stockPoints"));
		properties.setApiPath("/api/other/v2.0/");
		assertEquals("http://nav.test:7158/ZEN/api/other/v2.0/companies(" + COMPANY_ID + ")/stockPoints",
				properties.pageAddress("stockPoints", COMPANY_ID));
	}

	@Test
	@DisplayName("Step 10: without company-id, the id is looked up once by the company's name (quote doubled, encoded),"
			+ " then every page is under companies(id)")
	void companyByName() {
		properties.setCompanyId(null);
		properties.setCompany("ZEN SA'S");
		server.expect(requestTo("http://nav.test:7158/ZEN/api/sat/zsretail/v1.0/companies?$filter=name%20eq%20'ZEN%20SA''S'"))
				.andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
						"{\"value\":[{\"id\":\"" + COMPANY_ID + "\",\"name\":\"ZEN SA'S\"}]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(ROOT + "itemCategories")).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(ROOT + "stockPoints?$filter=locationCode%20eq%20'ARGENTE'"))
				.andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

		client.readCategories();
		client.readStockPoints("ARGENTE", null);

		server.verify(); // one lookup only
		assertEquals(COMPANY_ID, client.companyId());
	}

	@Test
	@DisplayName("Step 10: no company of that name: refused naming it and the two keys")
	void unknownCompany() {
		properties.setCompanyId(" ");
		properties.setCompany("NOBODY");
		server.expect(requestTo("http://nav.test:7158/ZEN/api/sat/zsretail/v1.0/companies?$filter=name%20eq%20'NOBODY'"))
				.andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

		ZenReadException failed = assertThrows(ZenReadException.class, () -> client.readCategories());
		assertTrue(failed.getMessage().startsWith("zen: no company named 'NOBODY' in the API"), failed.getMessage());
		assertTrue(failed.getMessage().endsWith("check erp.zen.company, or set erp.zen.company-id"), failed.getMessage());
	}

	@Test
	@DisplayName("An HTTP error names the page and the status; anything but GET is refused before it leaves")
	void errorsAndGetOnly() {
		server.expect(requestTo(ROOT + "itemCategories")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		ZenReadException failed = assertThrows(ZenReadException.class, () -> client.readCategories());
		assertTrue(failed.getMessage().startsWith("zen: reading the page itemCategories failed: HTTP 401"), failed.getMessage());
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> template.postForObject(ROOT + "stockPoints", "{}", String.class));
		assertTrue(refused.getMessage().contains("only reads from the ERP (GET)"), refused.getMessage());
	}
}
