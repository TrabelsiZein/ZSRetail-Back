package com.digithink.zsretail.erp.navpospages.config;

import java.io.IOException;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * ERP catalogue, step 5: the HTTP client of the "POS pages" connector. NTLM like the Dynamics NAV connector, with the two
 * timeouts of the settings, and an interceptor that refuses any method other than GET before it leaves: this connector
 * never writes to the ERP. Exists only with erp.navpospages.enabled=true; its own bean name, so no other RestTemplate is
 * affected.
 */
@Configuration
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class NavPosPagesConfig {

	public static final String REST_TEMPLATE = "navPosPagesRestTemplate";

	@Bean(REST_TEMPLATE)
	public RestTemplate navPosPagesRestTemplate(NavPosPagesProperties properties) {
		return restTemplate(properties);
	}

	/** The RestTemplate of these settings (also used by the tests). */
	public static RestTemplate restTemplate(NavPosPagesProperties properties) {
		CredentialsProvider credentials = new BasicCredentialsProvider();
		credentials.setCredentials(AuthScope.ANY, new NTCredentials(properties.getUsername(), properties.getPassword(),
				null, properties.getDomain()));
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
				HttpClientBuilder.create().setDefaultCredentialsProvider(credentials).build());
		factory.setConnectTimeout(properties.getConnectTimeoutSeconds() * 1000);
		factory.setReadTimeout(properties.getReadTimeoutSeconds() * 1000);
		RestTemplate template = new RestTemplate(factory);
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
