package com.digithink.zsretail.connector.zen.client;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.dto.ErpItemImagesDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 3.1.1 (item images): ZEN's products API (outside NAV, erp.zen.products.url), read for the items' images. A POST that
 * reads: {"skus": [..]} (the NAV item numbers; it filters exactly), with the header X-Api-Key (erp.zen.products.api-key);
 * the answer {"data": [{sku, title, onSell, onSellB2b, dateActiveB2C, dateActiveB2B, frontImg, detailsImg}]}, one row
 * per item (no images per variant). Each gives its sku (= our nav_item_no) and two public addresses, frontImg (the main
 * image) and detailsImg. Never "updatedAt": on ZEN's side any window answers the whole catalogue (10,893 products,
 * 3.3 MB, 2026-10-28) and overrides "skus".
 * <p>
 * Its own RestTemplate: no NAV login (the connector's other one sends NTLM to any host that asks), the connector's two
 * timeouts, and a guard that lets only a POST to that exact address leave. A failed call is a {@link ZenReadException}
 * naming the API and the HTTP status (never the key).
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenProductsClient {

	/** The label of the reads (summaries, errors). */
	public static final String LABEL = "products API";
	static final String API_KEY_HEADER = "X-Api-Key";
	static final String SKUS = "skus";
	static final String SKU = "sku";
	static final String FRONT_IMAGE = "frontImg";
	static final String DETAILS_IMAGE = "detailsImg";
	/** Where an answer that is not a list may hold its products, in this order. */
	static final String[] LIST_FIELDS = { "data", "products", "items", "results", "value" };

	private static final ObjectMapper JSON = new ObjectMapper();

	private final RestTemplate restTemplate;
	private final ZenProperties properties;

	@Autowired
	public ZenProductsClient(ZenProperties properties) {
		this(restTemplate(properties), properties);
	}

	/** With a given RestTemplate: used by the tests. */
	public ZenProductsClient(RestTemplate restTemplate, ZenProperties properties) {
		this.restTemplate = restTemplate;
		this.properties = properties;
	}

	/** Its address and key are set. */
	public boolean configured() {
		return properties.getProducts().configured();
	}

	/** The address read (never the key). */
	public String url() {
		return properties.getProducts().getUrl() == null ? null : properties.getProducts().getUrl().trim();
	}

	/** The images of these NAV item numbers; a number ZEN does not know is not answered. */
	public List<ErpItemImagesDTO> bySkus(List<String> skus) {
		ObjectNode body = JSON.createObjectNode();
		ArrayNode list = body.putArray(SKUS);
		skus.forEach(list::add);
		return products(post(body));
	}

	private JsonNode post(JsonNode body) {
		URI uri = URI.create(url());
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
		headers.set(API_KEY_HEADER, properties.getProducts().getApiKey().trim());
		try {
			ResponseEntity<byte[]> answer = restTemplate.exchange(uri, HttpMethod.POST,
					new HttpEntity<>(JSON.writeValueAsString(body), headers), byte[].class);
			byte[] text = answer.getBody();
			return text == null || text.length == 0 ? null : JSON.readTree(text);
		} catch (HttpStatusCodeException e) {
			String detail = e.getResponseBodyAsString(StandardCharsets.UTF_8);
			throw new ZenReadException(LABEL, "zen: reading the " + LABEL + " failed: HTTP " + e.getRawStatusCode() + " "
					+ e.getStatusText() + " (" + uri + ")" + (detail == null || detail.trim().isEmpty() ? ""
							: ": " + (detail.length() > 500 ? detail.substring(0, 500) : detail)), e);
		} catch (ResourceAccessException e) {
			throw new ZenReadException(LABEL, "zen: reading the " + LABEL + " failed: no answer or timeout ("
					+ e.getMessage() + ")", e);
		} catch (RestClientException | IOException e) {
			throw new ZenReadException(LABEL, "zen: reading the " + LABEL + " failed: " + e.getMessage(), e);
		}
	}

	/**
	 * The products of an answer: a list, or a list under data, products, items, results or value (also one level down
	 * when data is an object). A product without sku is left out; its images frontImg then detailsImg, blank ones left
	 * out (none: an empty list, the images removed).
	 */
	static List<ErpItemImagesDTO> products(JsonNode answer) {
		List<ErpItemImagesDTO> products = new ArrayList<>();
		JsonNode list = listOf(answer);
		if (list == null) {
			return products;
		}
		for (JsonNode product : list) {
			String sku = text(product, SKU);
			if (sku.isEmpty()) {
				continue;
			}
			List<String> urls = new ArrayList<>();
			for (String field : new String[] { FRONT_IMAGE, DETAILS_IMAGE }) {
				String url = text(product, field);
				if (!url.isEmpty() && !urls.contains(url)) {
					urls.add(url);
				}
			}
			products.add(new ErpItemImagesDTO(sku, urls));
		}
		return products;
	}

	private static JsonNode listOf(JsonNode answer) {
		if (answer == null || answer.isNull()) {
			return null;
		}
		if (answer.isArray()) {
			return answer;
		}
		for (String field : LIST_FIELDS) {
			JsonNode value = answer.get(field);
			if (value != null && value.isArray()) {
				return value;
			}
		}
		JsonNode data = answer.get("data");
		if (data != null && data.isObject()) {
			return listOf(data);
		}
		return null;
	}

	private static String text(JsonNode row, String field) {
		JsonNode value = row == null ? null : row.get(field);
		return value == null || value.isNull() ? "" : value.asText().trim();
	}

	/** No NAV login, the connector's timeouts, only a POST to the products address. */
	public static RestTemplate restTemplate(ZenProperties properties) {
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
				HttpClientBuilder.create().build());
		factory.setConnectTimeout(properties.getConnectTimeoutSeconds() * 1000);
		factory.setReadTimeout(properties.getReadTimeoutSeconds() * 1000);
		RestTemplate template = new RestTemplate(factory);
		template.getInterceptors().add(new PostOnlyTo(properties.getProducts().getUrl()));
		return template;
	}

	/** Refuses every request but a POST to this address (compared without a trailing /), before it is sent. */
	public static class PostOnlyTo implements ClientHttpRequestInterceptor {

		private final String url;

		public PostOnlyTo(String url) {
			this.url = url == null ? "" : strip(url.trim());
		}

		public boolean allowed(HttpMethod method, URI uri) {
			return method == HttpMethod.POST && !url.isEmpty() && url.equals(strip(uri.toString()));
		}

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			if (!allowed(request.getMethod(), request.getURI())) {
				throw new IllegalStateException("The zen connector only reads ZEN's products API by a POST to its"
						+ " address: " + request.getMethod() + " " + request.getURI() + " refused, nothing was sent.");
			}
			return execution.execute(request, body);
		}

		private static String strip(String value) {
			return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
		}
	}
}
