package com.digithink.zsretail.connector.zen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

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

/**
 * Item variants (ZEN), step 8: the addresses and filters of the reads of ZEN's API, against a mock server (no live
 * call): the rows of a location from a checkpoint (lastModifiedDateTime ge, in UTC), the next links followed, the
 * barcodes by Entry_No and by item and variant, a page given as a whole address, GET only.
 */
class ZenRestClientTest {

	static final String COMPANY = "89c8b192-3f2e-f111-9f24-7ced8db3f120";
	static final String ROOT = "http://nav.test:7158/ZEN/api/sat/zsretail/v1.0/companies(" + COMPANY + ")/";

	private ZenProperties properties;
	private RestTemplate template;
	private MockRestServiceServer server;
	private ZenRestClient client;

	@BeforeEach
	void setUp() {
		properties = new ZenProperties();
		properties.setBaseUrl("http://nav.test:7158/ZEN");
		properties.setCompany(COMPANY);
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
	@DisplayName("Barcodes: one page after an Entry_No; those of some items by Item_No and Variant_Code")
	void barcodes() {
		server.expect(requestTo(ROOT + "ItemBarCodePOS?$filter=Entry_No%20gt%20120&$orderby=Entry_No&$top=1000"
				+ "&$select=Item_No,Variant_Code,Cross_Reference_No,Entry_No")).andRespond(withSuccess(
						"{\"value\":[{\"Item_No\":\"70061\",\"Variant_Code\":\"001\",\"Cross_Reference_No\":\"9000002740592\","
								+ "\"Entry_No\":121}]}",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(ROOT + "ItemBarCodePOS?$filter=(Item_No%20eq%20'70061'%20and%20Variant_Code%20eq%20'001')"
				+ "%20or%20(Item_No%20eq%20'1000'%20and%20Variant_Code%20eq%20'')"
				+ "&$select=Item_No,Variant_Code,Cross_Reference_No,Entry_No"))
				.andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

		List<ZenBarcodeRow> page = client.readBarcodesAfter(120);
		client.readBarcodesOfItems(Arrays.asList("70061~001", "1000"));

		server.verify();
		assertEquals("001", page.get(0).getVariantCode());
		assertEquals(Long.valueOf(121), page.get(0).getEntryNo());
		assertEquals("(Item_No eq '70061' and Variant_Code eq '001') or (Item_No eq 'LS-10PC' and Variant_Code eq"
				+ " 'LS-10PC-B')", ZenRestClient.barcodesOfItemsFilter(Arrays.asList("70061~001", "LS-10PC~LS-10PC-B")));
	}

	@Test
	@DisplayName("A page given as a whole address is read there; the address of a page for the log")
	void wholeAddress() {
		properties.getPage().setBarcodes("http://nav.test:7048/ZEN/ODataV4/Company('ZEN')/ItemBarCodePOS");

		assertEquals("http://nav.test:7048/ZEN/ODataV4/Company('ZEN')/ItemBarCodePOS", client.pageUrl("http://nav.test:7048"
				+ "/ZEN/ODataV4/Company('ZEN')/ItemBarCodePOS"));
		assertEquals(ROOT + "stockPoints", client.pageUrl("stockPoints"));
		properties.setApiPath("/api/other/v2.0/");
		assertEquals("http://nav.test:7158/ZEN/api/other/v2.0/companies(" + COMPANY + ")/stockPoints",
				properties.pageAddress("stockPoints"));
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
