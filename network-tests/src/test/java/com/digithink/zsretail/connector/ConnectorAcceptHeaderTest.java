package com.digithink.zsretail.connector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.emtop.client.DynamicsNavRestClient;
import com.digithink.zsretail.connector.emtop.config.DynamicsNavConfig;
import com.digithink.zsretail.connector.emtop.config.DynamicsNavProperties;
import com.digithink.zsretail.connector.happyness.client.NavPosPagesRestClient;
import com.digithink.zsretail.connector.happyness.config.NavPosPagesConfig;
import com.digithink.zsretail.connector.happyness.config.NavPosPagesProperties;
import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.connector.parafendri.config.ParafendriConfig;
import com.digithink.zsretail.connector.parafendri.config.ParafendriProperties;
import com.digithink.zsretail.connector.zen.client.ZenRestClient;
import com.digithink.zsretail.connector.zen.config.ZenConfig;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.erp.api.ErpStoreLocation;

/**
 * Boot 4 migration, step 1.1: what each connector's reads ask of the ERP. Their GETs set no Accept header, so a
 * RestTemplate takes it from the converters on the classpath: with jackson-dataformat-xml in the WAR they also asked for
 * XML, first. Here, with every project's libraries, as in a WAR: JSON, never XML.
 * <p>
 * A mock proves what we send, not what NAV answers: that needs a live GET (docs/migration/boot4-migration.md, step 2.7).
 */
class ConnectorAcceptHeaderTest {

	private static final String NO_ROWS = "{\"value\":[]}";

	/** The server of this template, checking the one request it expects. */
	private static MockRestServiceServer expectJsonOnly(RestTemplate template) {
		MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
		server.expect(request -> assertAsksJsonOnly(request.getHeaders().getAccept()))
				.andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
		return server;
	}

	private static void assertAsksJsonOnly(List<MediaType> accept) {
		assertTrue(accept.stream().anyMatch(MediaType.APPLICATION_JSON::includes), "asks for JSON: " + accept);
		assertFalse(accept.stream().anyMatch(type -> type.getSubtype().contains("xml")), "never for XML: " + accept);
	}

	@Test
	@DisplayName("EMTOP: a read asks for JSON, never XML")
	void emtop() {
		DynamicsNavProperties properties = new DynamicsNavProperties();
		properties.setBaseUrl("http://nav.test/ODataV4");
		properties.setCompany("CRONUS");
		properties.setUsername("user");
		properties.setPassword("secret");
		RestTemplate template = new DynamicsNavConfig().dynamicsNavRestTemplate(properties);
		MockRestServiceServer server = expectJsonOnly(template);

		new DynamicsNavRestClient(template, properties, mock(ErpStoreLocation.class)).fetchItemFamilies();

		server.verify();
	}

	@Test
	@DisplayName("Happyness: a read asks for JSON, never XML")
	void happyness() {
		NavPosPagesProperties properties = new NavPosPagesProperties();
		properties.setBaseUrl("http://bc.test:7048/BC140/ODataV4");
		properties.setCompany("HAPPYNESS");
		properties.setUsername("user");
		properties.setPassword("secret");
		RestTemplate template = NavPosPagesConfig.restTemplate(properties);
		MockRestServiceServer server = expectJsonOnly(template);

		new NavPosPagesRestClient(template, properties).readCategories();

		server.verify();
	}

	@Test
	@DisplayName("ParaFendri: a read asks for JSON, never XML")
	void parafendri() {
		ParafendriProperties properties = new ParafendriProperties();
		properties.setBaseUrl("http://bc.test:7048/BC280");
		properties.setUsername("user");
		properties.setPassword("secret");
		RestTemplate template = ParafendriConfig.restTemplate(properties);
		MockRestServiceServer server = expectJsonOnly(template);

		new ParafendriRestClient(template, properties).readCompanies();

		server.verify();
	}

	@Test
	@DisplayName("ZEN: a read asks for JSON, never XML")
	void zen() {
		ZenProperties properties = new ZenProperties();
		properties.setBaseUrl("http://nav.test:7158/ZEN");
		properties.setCompany("ZEN");
		properties.setCompanyId("89c8b192-3f2e-f111-9f24-7ced8db3f120");
		properties.setUsername("user");
		properties.setPassword("secret");
		RestTemplate template = ZenConfig.restTemplate(properties);
		MockRestServiceServer server = expectJsonOnly(template);

		new ZenRestClient(template, properties).readCategories();

		server.verify();
	}
}
