package com.digithink.zsretail.connector.parafendri.config;

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

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

/**
 * ParaFendri, step 0: the HTTP client of the connector. NTLM, as the other NAV connectors, with the two timeouts of the
 * settings, but the login given to the host of base-url only (its port when it names one), never to any host that asks
 * (a next link elsewhere gets no login). An interceptor refuses any method other than GET before it leaves, but a POST
 * to the page salesOrders (step 6, the tickets): the connector writes nothing else to the ERP. Exists only with erp.connector=parafendri; its own bean name, so no other RestTemplate is
 * affected.
 */
@Configuration
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
public class ParafendriConfig {

	public static final String REST_TEMPLATE = "parafendriRestTemplate";

	@Bean(REST_TEMPLATE)
	public RestTemplate parafendriRestTemplate(ParafendriProperties properties) {
		return restTemplate(properties);
	}

	/** The RestTemplate of these settings (also used by the tests). */
	public static RestTemplate restTemplate(ParafendriProperties properties) {
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
				HttpClientBuilder.create().setDefaultCredentialsProvider(credentials(properties)).build());
		factory.setConnectTimeout(properties.getConnectTimeoutSeconds() * 1000);
		factory.setReadTimeout(properties.getReadTimeoutSeconds() * 1000);
		RestTemplate template = new RestTemplate(factory);
		template.getInterceptors().add(new GetOnly());
		return template;
	}

	/** The NTLM login, for the host of base-url (and its port when it names one) only. */
	public static CredentialsProvider credentials(ParafendriProperties properties) {
		URI base = URI.create(properties.getBaseUrl().trim());
		CredentialsProvider credentials = new BasicCredentialsProvider();
		credentials.setCredentials(new AuthScope(base.getHost(), base.getPort()), new NTCredentials(
				properties.getUsername(), properties.getPassword(), null, properties.getDomain()));
		return credentials;
	}

	/** Refuses every request that is not a GET, before it is sent. */
	public static class GetOnly implements ClientHttpRequestInterceptor {

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			if (request.getMethod() != HttpMethod.GET && !postAllowed(request)) {
				throw new IllegalStateException("The parafendri connector only reads from the ERP (GET), apart from"
						+ " posting the tickets to " + SALES_ORDERS + ": " + request.getMethod() + " " + request.getURI()
						+ " refused, nothing was sent.");
			}
			return execution.execute(request, body);
		}

		/** Step 6: a POST to the page salesOrders (the address ends with /salesOrders, no query), the only write. */
		static boolean postAllowed(HttpRequest request) {
			return request.getMethod() == HttpMethod.POST && request.getURI().getQuery() == null
					&& request.getURI().getPath() != null && request.getURI().getPath().endsWith("/" + SALES_ORDERS);
		}
	}

	/** Step 6: the sales orders page, the tickets posted with their lines (the connector's only write). */
	public static final String SALES_ORDERS = "salesOrders";
}
