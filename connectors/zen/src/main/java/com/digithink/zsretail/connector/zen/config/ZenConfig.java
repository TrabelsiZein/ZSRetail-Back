package com.digithink.zsretail.connector.zen.config;

import java.io.IOException;
import java.net.URI;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

/**
 * Item variants (ZEN), step 8: the HTTP client of ZEN's connector. NTLM, as the other NAV connectors, with the two
 * timeouts of the settings, and an interceptor that refuses any method other than GET before it leaves (3.1.1: but a
 * POST to the sales orders page, the stores' orders): this connector
 * writes nothing else to the ERP. Exists only with erp.connector=zen; its own bean name, so no other RestTemplate is
 * affected.
 */
@Configuration
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenConfig {

	public static final String REST_TEMPLATE = "zenRestTemplate";

	@Bean(REST_TEMPLATE)
	public RestTemplate zenRestTemplate(ZenProperties properties) {
		return restTemplate(properties);
	}

	/** The RestTemplate of these settings (also used by the tests). */
	public static RestTemplate restTemplate(ZenProperties properties) {
		CredentialsProvider credentials = new BasicCredentialsProvider();
		credentials.setCredentials(AuthScope.ANY, new NTCredentials(properties.getUsername(), properties.getPassword(),
				null, properties.getDomain()));
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
				HttpClientBuilder.create().setDefaultCredentialsProvider(credentials).build());
		factory.setConnectTimeout(properties.getConnectTimeoutSeconds() * 1000);
		factory.setReadTimeout(properties.getReadTimeoutSeconds() * 1000);
		RestTemplate template = new RestTemplate(factory);
		template.getInterceptors().add(new GetOnly(properties.getSalesorders().getPath()));
		return template;
	}

	/**
	 * Refuses every request that is not a GET, before it is sent. 3.1.1 (store orders): but a POST to the sales orders
	 * page (salesorders.path: its address ends with that name, or is that whole address), the only write of this
	 * connector.
	 */
	public static class GetOnly implements ClientHttpRequestInterceptor {

		/** The path a POST may go to; null: none. */
		private final String postPath;

		/** GET only, no POST anywhere. */
		public GetOnly() {
			this(null);
		}

		/** GET, and POST to the page of this name or whole address. */
		public GetOnly(String salesOrdersPage) {
			this.postPath = postPathOf(salesOrdersPage);
		}

		/** The path of a whole address; "/" + the name of a page under the company; null for none. */
		public static String postPathOf(String page) {
			String name = page == null ? "" : page.trim();
			if (name.isEmpty()) {
				return null;
			}
			if (name.startsWith("http://") || name.startsWith("https://")) {
				String path = URI.create(name).getPath();
				return path == null || path.isEmpty() ? null : path;
			}
			while (name.startsWith("/")) {
				name = name.substring(1);
			}
			while (name.endsWith("/")) {
				name = name.substring(0, name.length() - 1);
			}
			return name.isEmpty() ? null : "/" + name;
		}

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			boolean salesOrder = request.getMethod() == HttpMethod.POST && postPath != null
					&& request.getURI().getPath() != null && request.getURI().getPath().endsWith(postPath);
			if (request.getMethod() != HttpMethod.GET && !salesOrder) {
				throw new IllegalStateException("The zen connector only reads from the ERP (GET), apart from posting the"
						+ " stores' orders to their page: " + request.getMethod() + " " + request.getURI()
						+ " refused, nothing was sent.");
			}
			return execution.execute(request, body);
		}
	}
}
