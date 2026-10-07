package com.digithink.zsretail.erp.navpospages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.erp.navpospages.client.NavPosPagesReadException;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesRestClient;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesConfig;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;

/**
 * ERP catalogue, step 5: the three reads against a mocked server (the RestTemplate of the connector, its GET-only
 * interceptor included): the URLs ($select, $filter, $orderby, $top), GET, the next link, an HTTP error, a timeout.
 */
class NavPosPagesRestClientTest {

	private NavPosPagesProperties properties;
	private RestTemplate template;
	private MockRestServiceServer server;
	private NavPosPagesRestClient client;

	@BeforeEach
	void setUp() {
		properties = NavPosPagesTestSupport.properties();
		template = NavPosPagesConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		client = new NavPosPagesRestClient(template, properties);
	}

	@Test
	@DisplayName("Categories: $select of the four fields, GET, the rows of the sample")
	void categories() {
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "ItemCategory?$select=Code,Description,Parent_Category,Type"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(NavPosPagesTestSupport.sample("ItemCategory"), MediaType.APPLICATION_JSON));
		List<NavPosCategoryRow> rows = client.readCategories();
		server.verify();
		assertEquals(50, rows.size());
		assertEquals("CAT-BEB", rows.get(0).getCode());
		assertEquals("Categorie", rows.get(0).getType());
		assertEquals("FAM-ACB-BEB", rows.get(2).getParentCategory());
	}

	@Test
	@DisplayName("Items: $filter on the location (a quote doubled), $select of the six fields, the next link followed")
	void itemsAndNextLink() {
		properties.setLocationCode(" O'NEIL ");
		String first = NavPosPagesTestSupport.COMPANY_URL + "PointStockPOS?$filter=Location_Code%20eq%20'O''NEIL'"
				+ "&$select=Item_No,Variant_Code,Description,Unit_Price,Family,Subfamily";
		String next = NavPosPagesTestSupport.COMPANY_URL
				+ "PointStockPOS?$filter=Location_Code%20eq%20'O''NEIL'&$select=Item_No&$skiptoken='000002'";
		server.expect(requestTo(first)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
				"{\"value\":[{\"Item_No\":\"000001\",\"Variant_Code\":\"\",\"Description\":\"A   \",\"Unit_Price\":2.45,"
						+ "\"Family\":\"F\",\"Subfamily\":\"S\",\"Inventory\":3}],\"@odata.nextLink\":\"" + next + "\"}",
				MediaType.APPLICATION_JSON));
		server.expect(requestTo(next)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
				"{\"value\":[{\"Item_No\":\"000002\",\"Unit_Price\":10}]}", MediaType.APPLICATION_JSON));
		List<NavPosStockRow> rows = client.readItems();
		server.verify();
		assertEquals(2, rows.size());
		assertEquals("A   ", rows.get(0).getDescription());
		assertEquals(0, new java.math.BigDecimal("2.45").compareTo(rows.get(0).getUnitPrice()));
		assertEquals("000002", rows.get(1).getItemNo());
	}

	@Test
	@DisplayName("Items of the sample (location DA-MG-GREM): 50 rows")
	void itemsSample() {
		properties.setLocationCode("DA-MG-GREM");
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "PointStockPOS?$filter=Location_Code%20eq%20'DA-MG-GREM'"
				+ "&$select=Item_No,Variant_Code,Description,Unit_Price,Family,Subfamily"))
				.andRespond(withSuccess(NavPosPagesTestSupport.sample("PointStockPOS"), MediaType.APPLICATION_JSON));
		assertEquals(50, client.readItems().size());
		server.verify();
	}

	@Test
	@DisplayName("Barcodes: one page after an entry number, $orderby and $top, never the next link")
	void barcodes() {
		properties.setBarcodePageSize(250);
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "ItemBarCodePOS?$filter=Entry_No%20gt%2020000"
				+ "&$orderby=Entry_No&$top=250&$select=Item_No,Cross_Reference_No,Entry_No"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(NavPosPagesTestSupport.sample("ItemBarCodePOS").replace("{\"@odata.context\"",
						"{\"@odata.nextLink\":\"http://bc.test/never\",\"@odata.context\""), MediaType.APPLICATION_JSON));
		List<NavPosBarcodeRow> rows = client.readBarcodesAfter(20000);
		server.verify(); // the next link was not followed
		assertEquals(50, rows.size());
		assertEquals(Long.valueOf(20659), rows.get(0).getEntryNo());
		assertEquals("0000016", rows.get(0).getCrossReferenceNo());
	}

	@Test
	@DisplayName("Step 6: the barcodes of some items, $filter on Item_No (quotes doubled), every row, next link followed")
	void barcodesOfItems() {
		String first = NavPosPagesTestSupport.COMPANY_URL + "ItemBarCodePOS?$filter=Item_No%20eq%20'000002'%20or%20Item_No"
				+ "%20eq%20'O''B'&$select=Item_No,Cross_Reference_No,Entry_No";
		String next = NavPosPagesTestSupport.COMPANY_URL + "ItemBarCodePOS?$skiptoken='2'";
		server.expect(requestTo(first)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
				"{\"value\":[{\"Item_No\":\"000002\",\"Cross_Reference_No\":\"000002\",\"Entry_No\":20661}],"
						+ "\"@odata.nextLink\":\"" + next + "\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(next)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
				"{\"value\":[{\"Item_No\":\"O'B\",\"Cross_Reference_No\":\"619\",\"Entry_No\":3}]}", MediaType.APPLICATION_JSON));
		List<NavPosBarcodeRow> rows = client.readBarcodesOfItems(java.util.Arrays.asList("000002", "O'B"));
		server.verify();
		assertEquals(2, rows.size());
		assertTrue(client.readBarcodesOfItems(new java.util.ArrayList<>()).isEmpty(), "no call without items");
		assertEquals(NavPosPagesTestSupport.COMPANY_URL + "PointStockPOS", client.pageUrl("PointStockPOS"));
	}

	@Test
	@DisplayName("An HTTP error names the page and the status; a timeout names the page")
	void errors() {
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "PointStockPOS?$filter=Location_Code%20eq%20'FRANCHISE'"
				+ "&$select=Item_No,Variant_Code,Description,Unit_Price,Family,Subfamily"))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED));
		NavPosPagesReadException error = assertThrows(NavPosPagesReadException.class, () -> client.readItems());
		assertEquals("PointStockPOS", error.getPage());
		assertTrue(error.getMessage().contains("reading the page PointStockPOS failed: HTTP 401"), error.getMessage());

		server.reset();
		server.expect(requestTo(NavPosPagesTestSupport.COMPANY_URL + "ItemCategory?$select=Code,Description,Parent_Category,Type"))
				.andRespond(request -> {
					throw new SocketTimeoutException("Read timed out");
				});
		NavPosPagesReadException timeout = assertThrows(NavPosPagesReadException.class, () -> client.readCategories());
		assertTrue(timeout.getMessage().contains("reading the page ItemCategory failed: no answer or timeout"),
				timeout.getMessage());
	}

	@Test
	@DisplayName("Any method other than GET is refused before it leaves: nothing reaches the server")
	void getOnly() {
		for (HttpMethod refused : new HttpMethod[] { HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE }) {
			IllegalStateException error = assertThrows(IllegalStateException.class,
					() -> template.exchange(NavPosPagesTestSupport.COMPANY_URL + "ItemCategory", refused, null, String.class));
			assertTrue(error.getMessage().startsWith("The navpospages connector only reads from the ERP (GET): " + refused),
					error.getMessage());
		}
		server.verify(); // no request expected, none made
	}
}
