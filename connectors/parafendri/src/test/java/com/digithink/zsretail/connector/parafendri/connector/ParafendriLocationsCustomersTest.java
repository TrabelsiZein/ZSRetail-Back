package com.digithink.zsretail.connector.parafendri.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.parafendri.client.ParafendriReadException;
import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.connector.parafendri.config.ParafendriConfig;
import com.digithink.zsretail.connector.parafendri.config.ParafendriProperties;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCustomerRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriLocationRow;
import com.digithink.zsretail.connector.parafendri.mapper.ParafendriMapper;
import com.digithink.zsretail.erp.api.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.api.dto.ErpLocationDTO;
import com.digithink.zsretail.erp.api.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.api.dto.PullOperationResult;

/**
 * ParaFendri: the two reads the till relies on, against a mock server. fetchLocations: every location of the company
 * (locations.json), code and name, none marked as the default. fetchCustomers: one row only, the account of the
 * DEFAULT_LOCATION (its customerNo from stockPoints, then customers?$filter=number eq 'X'), blocked as inactive; the
 * page read whole when number is not filterable, the other points de vente's accounts never kept; no DEFAULT_LOCATION
 * or no customerNo, no row.
 */
class ParafendriLocationsCustomersTest {

	static final String LOCATIONS = ParafendriTicketExportTest.COMPANY + "locations?$select=code,name,lastModifiedDateTime";
	static final String CUSTOMERS = ParafendriTicketExportTest.COMPANY + "customers";
	static final String CUSTOMER_4110001 = CUSTOMERS + "?$filter=number%20eq%20'4110001'";
	static final String STOCK_POINT_4110001 = "{\"value\":[{\"locationCode\":\"PV-GREMDA\",\"itemNo\":\"ITM001\","
			+ "\"customerNo\":\" 4110001 \"}]}";

	private MockRestServiceServer server;
	private ParafendriConnector connector;
	private String location = "PV-GREMDA";
	private final ParafendriMapper mapper = new ParafendriMapper();

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
				ParafendriConnectorTest.location(() -> location));
	}

	@AfterEach
	void tearDown() {
		connector.clearLastPullOperationResult();
	}

	static String sample(String name) throws IOException {
		try (InputStream in = ParafendriLocationsCustomersTest.class.getResourceAsStream("/parafendri/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String only(String sampleName, int row) throws IOException {
		String sample = sample(sampleName);
		String[] lines = sample.split("\n");
		String line = Arrays.stream(lines).filter(l -> l.trim().startsWith("{ \"@odata.etag\"")).skip(row).findFirst()
				.get().trim();
		return "{\"value\":[" + (line.endsWith(",") ? line.substring(0, line.length() - 1) : line) + "]}";
	}

	// ─── Locations ───────────────────────────────────────────────

	@Test
	@DisplayName("Every location of the company, code and name (ERP id the code), none marked active or default; the"
			+ " read and its address in the communications log")
	void locations() throws IOException {
		server.expect(requestTo(LOCATIONS)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(sample("locations.json"), MediaType.APPLICATION_JSON));
		ErpSyncFilter fromCheckpoint = new ErpSyncFilter();
		fromCheckpoint.setUpdatedAfter(OffsetDateTime.parse("2026-10-21T00:00:00Z"));
		List<ErpLocationDTO> rows = connector.fetchLocations(fromCheckpoint);
		server.verify();
		assertEquals(Arrays.asList("PV-GREMDA", "PV-LAFRANE", "PV-MALL-SF", "PV-NABEUL", "PV-SOUSSE", "PV-TANIOUR"),
				rows.stream().map(ErpLocationDTO::getCode).collect(Collectors.toList()),
				"always whole: PV-TANIOUR (never changed, 0001-01-01) too");
		ErpLocationDTO gremda = rows.get(0);
		assertEquals("PV-GREMDA", gremda.getExternalId());
		assertEquals("PV Gremda", gremda.getName());
		assertNull(gremda.getActive(), "the import keeps the location's own flag");
		PullOperationResult<?> logged = connector.getLastPullOperationResult();
		assertEquals(LOCATIONS, logged.getUrl());
		assertEquals(6, ((Map<?, ?>) logged.getRawResponse()).get("kept"));
	}

	@Test
	@DisplayName("Mapping: a blank code or a code read twice (its case ignored) left out, the name trimmed")
	void locationsMapping() {
		ParafendriLocationRow blank = locationRow(" ", "Sans code");
		List<ErpLocationDTO> rows = mapper.locations(Arrays.asList(locationRow(" PV-A ", " PV A "), blank,
				locationRow("pv-a", "again"))).getRows();
		assertEquals(1, rows.size());
		assertEquals("PV-A", rows.get(0).getCode());
		assertEquals("PV A", rows.get(0).getName());
		assertEquals(Integer.valueOf(1), mapper.locations(Arrays.asList(blank)).getLeftOut().get(ParafendriMapper.BLANK_CODE));
	}

	private static ParafendriLocationRow locationRow(String code,
			String name) {
		ParafendriLocationRow row =
				new ParafendriLocationRow();
		row.setCode(code);
		row.setName(name);
		return row;
	}

	// ─── The point de vente's customer ──────────────────────────

	@Test
	@DisplayName("One row: the customerNo of DEFAULT_LOCATION from stockPoints ($top=1), then customers?$filter=number eq"
			+ " it; code and ERP id the number, the name, active (_x0020_), its time")
	void customer() throws IOException {
		server.expect(requestTo(ParafendriTicketExportTest.CUSTOMER)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(STOCK_POINT_4110001, MediaType.APPLICATION_JSON));
		server.expect(requestTo(CUSTOMER_4110001)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(only("customers.json", 0), MediaType.APPLICATION_JSON));
		List<ErpCustomerDTO> rows = connector.fetchCustomers(new ErpSyncFilter());
		server.verify();
		assertEquals(1, rows.size());
		ErpCustomerDTO customer = rows.get(0);
		assertEquals("4110001", customer.getCode());
		assertEquals("4110001", customer.getExternalId());
		assertEquals("PV Gremda (clients passagers)", customer.getName());
		assertTrue(customer.getActive());
		assertEquals(OffsetDateTime.parse("2026-10-20T09:15:02.100Z"), customer.getLastModifiedAt());
		PullOperationResult<?> logged = connector.getLastPullOperationResult();
		assertEquals(CUSTOMER_4110001, logged.getUrl());
		Map<?, ?> summary = (Map<?, ?>) logged.getRawResponse();
		assertEquals("PV-GREMDA", summary.get("location"));
		assertEquals(false, summary.get("wholePage"));
	}

	@Test
	@DisplayName("number not filterable in BC (HTTP 400): the page read whole, only the point de vente's account kept,"
			+ " never the other points de vente's")
	void customerWholePage() throws IOException {
		server.expect(requestTo(ParafendriTicketExportTest.CUSTOMER))
				.andRespond(withSuccess(STOCK_POINT_4110001, MediaType.APPLICATION_JSON));
		server.expect(requestTo(CUSTOMER_4110001)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":\"BadRequest_NotSupported\","
						+ "\"message\":\"Field 'number' is not filterable.\"}}"));
		server.expect(requestTo(CUSTOMERS)).andRespond(withSuccess(sample("customers.json"), MediaType.APPLICATION_JSON));
		List<ErpCustomerDTO> rows = connector.fetchCustomers(null);
		server.verify();
		assertEquals(Collections.singletonList("4110001"),
				rows.stream().map(ErpCustomerDTO::getCode).collect(Collectors.toList()));
		Map<?, ?> summary = (Map<?, ?>) connector.getLastPullOperationResult().getRawResponse();
		assertEquals(true, summary.get("wholePage"));
		assertEquals(3, summary.get("read"));
		assertEquals(Collections.singletonMap(ParafendriMapper.OTHER_CUSTOMER, 2), summary.get("leftOut"));
	}

	@Test
	@DisplayName("Another error of the customers read is thrown (the job fails, its message names the page)")
	void customerReadFails() {
		server.expect(requestTo(ParafendriTicketExportTest.CUSTOMER))
				.andRespond(withSuccess(STOCK_POINT_4110001, MediaType.APPLICATION_JSON));
		server.expect(requestTo(CUSTOMER_4110001)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		assertThrows(ParafendriReadException.class, () -> connector.fetchCustomers(null));
		server.verify();
	}

	@Test
	@DisplayName("No DEFAULT_LOCATION: no row, nothing read; no customerNo on the stockPoints rows: no row, customers"
			+ " not read")
	void noCustomer() {
		location = " ";
		server.expect(never(), requestTo(ParafendriTicketExportTest.CUSTOMER));
		assertTrue(connector.fetchCustomers(null).isEmpty());
		server.verify();

		server.reset();
		location = "PV-GREMDA";
		server.expect(requestTo(ParafendriTicketExportTest.CUSTOMER))
				.andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
		assertTrue(connector.fetchCustomers(null).isEmpty());
		server.verify();
	}

	@Test
	@DisplayName("Mapping: blocked Ship / Invoice / All inactive, blank or _x0020_ active; the number's case and spaces"
			+ " ignored; a second row of the number, a blank number left out; 0001-01-01 no time")
	void customerMapping() {
		assertFalse(customerOf("All").getActive());
		assertFalse(customerOf("Ship").getActive());
		assertFalse(customerOf("Invoice").getActive());
		assertTrue(customerOf("_x0020_").getActive());
		assertTrue(customerOf(" ").getActive());
		assertTrue(customerOf(null).getActive());

		ParafendriCustomerRow first = customerRow(" c-1 ", "_x0020_");
		first.setLastModifiedDateTime("0001-01-01T00:00:00Z");
		List<ErpCustomerDTO> rows = mapper.customer("C-1", Arrays.asList(customerRow("", null), first,
				customerRow("C-1", "All"))).getRows();
		assertEquals(1, rows.size());
		assertEquals("c-1", rows.get(0).getCode());
		assertTrue(rows.get(0).getActive(), "the first row of the number");
		assertNull(rows.get(0).getLastModifiedAt());
	}

	private ErpCustomerDTO customerOf(String blocked) {
		return mapper.customer("4110001", Collections.singletonList(customerRow("4110001", blocked))).getRows().get(0);
	}

	private static ParafendriCustomerRow customerRow(String number, String blocked) {
		ParafendriCustomerRow row = new ParafendriCustomerRow();
		row.setNumber(number);
		row.setName("Name " + number);
		row.setBlocked(blocked);
		return row;
	}
}
