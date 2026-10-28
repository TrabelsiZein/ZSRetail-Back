package com.digithink.zsretail.connector.zen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;
import com.digithink.zsretail.erp.api.dto.ErpItemImagesDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.1.1 (item images): ZEN's products API against a mock server (no live call), with the client's own RestTemplate.
 * The sample src/test/resources/zen/products.json has the shape of the live answer (checked by Zein from Chrome,
 * 2026-10-28: {"data": [{sku, title, onSell, onSellB2b, dateActiveB2C, dateActiveB2B, frontImg, detailsImg}]}, flat,
 * images per item only); the first product's values are those of ZEN's email of 2026-10-27, the two others made up
 * (an empty detailsImg, no image). The body ({"skus"} alone, never "updatedAt"), the X-Api-Key header, the shapes of an
 * answer, the errors (never the key), the guard (a POST to that address only), and the catalogue source reading images
 * only when both keys are set.
 */
class ZenProductsClientTest {

	static final String URL = "https://api-zentral.zen.com.tn/api/products/advanced-search-zs";
	static final String KEY = "test-key-not-the-real-one";
	static final String FRONT = "https://images.zen.com.tn/uploads/8_U2_A9282_9d32e2eb62.JPG";
	static final String DETAILS = "https://images.zen.com.tn/uploads/8_U2_A9282_14ea5b40fc.JPG";

	private ZenProperties properties;
	private MockRestServiceServer server;
	private ZenProductsClient client;

	@BeforeEach
	void setUp() {
		properties = new ZenProperties();
		properties.getProducts().setUrl(URL);
		properties.getProducts().setApiKey(" " + KEY + " ");
		RestTemplate template = ZenProductsClient.restTemplate(properties);
		server = MockRestServiceServer.bindTo(template).build();
		client = new ZenProductsClient(template, properties);
	}

	static String sample() throws IOException {
		try (InputStream in = ZenProductsClientTest.class.getResourceAsStream("/zen/products.json")) {
			return new String(StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("By skus: POST {skus} alone (never updatedAt) with X-Api-Key; the answer under data; frontImg then"
			+ " detailsImg, blanks left out, none: an empty list")
	void bySkus() throws IOException {
		server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
				.andExpect(header("X-Api-Key", KEY))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(content().json("{\"skus\":[\"40ZFEBF07\",\"43ZFCHD17\",\"28EZFDTD02\"]}", true))
				.andExpect(jsonPath("$.updatedAt").doesNotExist())
				.andRespond(withSuccess(sample(), MediaType.APPLICATION_JSON));
		List<ErpItemImagesDTO> products = client.bySkus(Arrays.asList("40ZFEBF07", "43ZFCHD17", "28EZFDTD02"));
		server.verify();
		assertEquals(3, products.size());
		assertEquals(new ErpItemImagesDTO("40ZFEBF07", Arrays.asList(FRONT, DETAILS)), products.get(0));
		assertEquals(Collections.singletonList("https://images.zen.com.tn/uploads/43ZFCHD17_front.JPG"),
				products.get(1).getUrls(), "an empty detailsImg left out");
		assertEquals(Collections.emptyList(), products.get(2).getUrls(), "no image: its rows will be removed");
	}

	@Test
	@DisplayName("The answer's shapes: a list; under data, products, items, results or value; data.products; a product"
			+ " without sku left out; anything else: none")
	void shapes() throws IOException {
		ObjectMapper json = new ObjectMapper();
		String one = "[{\"sku\":\"A\",\"frontImg\":\"" + FRONT + "\"},{\"title\":\"no sku\",\"frontImg\":\"x\"}]";
		for (String answer : Arrays.asList(one, "{\"data\":" + one + "}", "{\"products\":" + one + "}",
				"{\"items\":" + one + "}", "{\"results\":" + one + ",\"total\":1}", "{\"value\":" + one + "}",
				"{\"data\":{\"products\":" + one + ",\"page\":1}}")) {
			assertEquals(Collections.singletonList(new ErpItemImagesDTO("A", Collections.singletonList(FRONT))),
					ZenProductsClient.products(json.readTree(answer)), answer);
		}
		assertTrue(ZenProductsClient.products(json.readTree("{\"message\":\"ok\"}")).isEmpty());
		assertTrue(ZenProductsClient.products(null).isEmpty());
	}

	@Test
	@DisplayName("A refusal: ZenReadException naming the API and the status, NAV's body (UTF-8), never the key")
	void refused() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
				.contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Clé invalide\"}".getBytes(StandardCharsets.UTF_8)));
		ZenReadException e = assertThrows(ZenReadException.class, () -> client.bySkus(Collections.singletonList("A")));
		assertTrue(e.getMessage().startsWith("zen: reading the products API failed: HTTP 401"), e.getMessage());
		assertTrue(e.getMessage().contains("Clé invalide"), e.getMessage());
		assertFalse(e.getMessage().contains(KEY), "never the key");
	}

	@Test
	@DisplayName("No answer: ZenReadException with the timeout (the engine reads it again once)")
	void timeout() {
		server.expect(requestTo(URL)).andRespond(request -> {
			throw new SocketTimeoutException("Read timed out");
		});
		ZenReadException e = assertThrows(ZenReadException.class, () -> client.bySkus(Collections.singletonList("A")));
		assertTrue(e.getMessage().contains("no answer or timeout"), e.getMessage());
	}

	@Test
	@DisplayName("The guard: a POST to that address only (a trailing / allowed); a GET, or a POST elsewhere, refused")
	void guard() {
		ZenProductsClient.PostOnlyTo guard = new ZenProductsClient.PostOnlyTo(URL + "/");
		assertTrue(guard.allowed(HttpMethod.POST, URI.create(URL)));
		assertFalse(guard.allowed(HttpMethod.GET, URI.create(URL)));
		assertFalse(guard.allowed(HttpMethod.POST, URI.create("https://api-zentral.zen.com.tn/api/orders")));
		assertFalse(guard.allowed(HttpMethod.POST, URI.create("http://192.168.10.124:7048/BC280/api")));
		assertFalse(new ZenProductsClient.PostOnlyTo(null).allowed(HttpMethod.POST, URI.create(URL)));
		RestTemplate template = ZenProductsClient.restTemplate(properties);
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> template.getForObject(URL, String.class));
		assertTrue(refused.getMessage().contains("refused, nothing was sent"), refused.getMessage());
	}

	@Test
	@DisplayName("The catalogue source reads images only with both keys set (and a client); its label names the address")
	void source() {
		ZenCatalogueSource source = new ZenCatalogueSource(null, new ZenMapper(19), properties, client);
		assertTrue(source.readsItemImages());
		assertEquals("products API " + URL, source.itemImagesLabel());
		properties.getProducts().setApiKey(" ");
		assertFalse(source.readsItemImages(), "no key: not read");
		assertFalse(new ZenCatalogueSource(null, new ZenMapper(19), properties).readsItemImages(), "no client");
	}
}
