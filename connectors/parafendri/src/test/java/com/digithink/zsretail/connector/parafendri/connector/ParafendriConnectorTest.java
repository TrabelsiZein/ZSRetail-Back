package com.digithink.zsretail.connector.parafendri.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import com.digithink.zsretail.connector.parafendri.mapper.ParafendriMapper;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.api.dto.PullOperationResult;

/**
 * ParaFendri, step 2: the four fetches of the tree for the job IMPORT_CATALOGUE, against a mock server (the sample
 * itemCategories.json): GET companies(id)/itemCategories once per fetch (no cache), each its level; the read handed to
 * the communications log (the address, the counts, the reasons, the rows); a failed read names the page and keeps the
 * address for the log.
 */
class ParafendriConnectorTest {

	static final String COMPANY_ID = "6b29fc40-ca47-1067-b31d-00dd010662da";
	static final String TREE = "http://bc.test:7048/BC280/api/sat/zsretail/v1.0/companies(" + COMPANY_ID
			+ ")/itemCategories";

	private MockRestServiceServer server;
	private ParafendriConnector connector;

	@BeforeEach
	void setUp() {
		ParafendriProperties properties = new ParafendriProperties();
		properties.setBaseUrl("http://bc.test:7048/BC280");
		properties.setCompanyId(COMPANY_ID);
		properties.setUsername("user");
		properties.setPassword("secret");
		RestTemplate template = ParafendriConfig.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		connector = new ParafendriConnector(new ParafendriRestClient(template, properties));
	}

	@AfterEach
	void clear() {
		connector.clearLastPullOperationResult();
	}

	private static String sample() throws IOException {
		try (InputStream in = ParafendriConnectorTest.class.getResourceAsStream("/parafendri/itemCategories.json")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("The four levels, each fetch one GET of the page (no cache), the filter of the job not used")
	void fourLevels() throws IOException {
		server.expect(times(4), requestTo(TREE)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(sample(), MediaType.APPLICATION_JSON));
		ErpSyncFilter filter = new ErpSyncFilter();
		assertEquals("COS, HYG", connector.fetchItemCategories(filter).stream().map(ErpItemCategoryDTO::getCode)
				.collect(Collectors.joining(", ")));
		List<ErpItemFamilyDTO> families = connector.fetchItemFamilies(filter);
		assertEquals("COS-COF COS, COS-PAR COS, FAM-ORPH null", families.stream()
				.map(f -> f.getCode() + " " + f.getCategoryExternalId()).collect(Collectors.joining(", ")));
		assertEquals("COS-COF-PAR COS-COF, COS-PAR-HOM COS-PAR, FAM-ORPH-SF FAM-ORPH",
				connector.fetchItemSubFamilies(filter).stream().map(s -> s.getCode() + " " + s.getFamilyExternalId())
						.collect(Collectors.joining(", ")));
		List<ErpItemSectionDTO> sections = connector.fetchItemSections(filter);
		assertEquals("COS-COF-PAR-LUX COS-COF-PAR", sections.stream()
				.map(s -> s.getCode() + " " + s.getSubFamilyExternalId()).collect(Collectors.joining(", ")));
		server.verify();
	}

	@Test
	@DisplayName("The communications log: the address, then read, kept, left out by reason, notes and the rows")
	@SuppressWarnings("unchecked")
	void handedToTheLog() throws IOException {
		server.expect(requestTo(TREE)).andRespond(withSuccess(sample(), MediaType.APPLICATION_JSON));
		List<ErpItemSubFamilyDTO> rows = connector.fetchItemSubFamilies(null);
		PullOperationResult<?> pull = connector.getLastPullOperationResult();
		assertEquals(TREE, pull.getUrl());
		assertEquals(rows, pull.getData());
		Map<String, Object> summary = (Map<String, Object>) pull.getRawResponse();
		assertEquals(4, summary.get("read"));
		assertEquals(3, summary.get("kept"));
		assertEquals(Collections.singletonMap(ParafendriMapper.PARENT_NOT_FAMILY, 1), summary.get("leftOut"));
		assertEquals(Collections.emptyMap(), summary.get("notes"));
		assertEquals(rows, summary.get("rows"));
		connector.clearLastPullOperationResult();
		assertNull(connector.getLastPullOperationResult());
	}

	@Test
	@DisplayName("A failed read: the page and the status named, the address kept for the log")
	void failedRead() {
		server.expect(requestTo(TREE)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		ParafendriReadException failed = assertThrows(ParafendriReadException.class,
				() -> connector.fetchItemFamilies(null));
		assertEquals("itemCategories", failed.getPage());
		assertEquals(Integer.valueOf(503), failed.getStatus());
		assertEquals(TREE, connector.getLastPullOperationResult().getUrl());
		assertNull(connector.getLastPullOperationResult().getRawResponse());
	}

	@Test
	@DisplayName("The other fetches stay empty and send nothing (step 2 is the tree only)")
	void othersEmpty() {
		assertEquals(Collections.emptyList(), connector.fetchItems(null));
		assertEquals(Collections.emptyList(), connector.fetchItemBarcodes(null));
		server.verify();
	}
}
