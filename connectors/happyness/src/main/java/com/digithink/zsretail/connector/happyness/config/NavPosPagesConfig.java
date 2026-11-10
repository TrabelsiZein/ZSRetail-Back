package com.digithink.zsretail.connector.happyness.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.happyness.HappynessConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpHttp;

/**
 * ERP catalogue, step 5: the HTTP client of the "POS pages" connector. NTLM like the Dynamics NAV connector, with the two
 * timeouts of the settings, and an interceptor that refuses any method other than GET before it leaves: this connector
 * never writes to the ERP. Exists only with erp.connector=happyness; its own bean name, so no other RestTemplate is
 * affected.
 */
@Configuration
@ConditionalOnErpConnector(HappynessConnectorDescriptor.NAME)
public class NavPosPagesConfig {

	public static final String REST_TEMPLATE = "navPosPagesRestTemplate";

	@Bean(REST_TEMPLATE)
	public RestTemplate navPosPagesRestTemplate(NavPosPagesProperties properties) {
		return restTemplate(properties);
	}

	/** The RestTemplate of these settings (also used by the tests). */
	public static RestTemplate restTemplate(NavPosPagesProperties properties) {
		RestTemplate template = new RestTemplate(ErpHttp.requestFactory(
				ErpHttp.ntlm(properties.getUsername(), properties.getPassword(), properties.getDomain()),
				properties.getConnectTimeoutSeconds(), properties.getReadTimeoutSeconds()));
		template.getInterceptors().add(new GetOnly());
		return template;
	}

	/** Refuses every request that is not a GET, before it is sent. */
	public static class GetOnly implements ClientHttpRequestInterceptor {

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			if (request.getMethod() != HttpMethod.GET) {
				throw new IllegalStateException("The navpospages connector only reads from the ERP (GET): "
						+ request.getMethod() + " " + request.getURI() + " refused, nothing was sent.");
			}
			return execution.execute(request, body);
		}
	}
}
