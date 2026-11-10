package com.digithink.zsretail.connector.parafendri.config;

import java.io.IOException;
import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpHttp;

/**
 * ParaFendri, step 0: the HTTP client of the connector. NTLM, as the other NAV connectors, with the two timeouts of the
 * settings, but the login given to the host of base-url only (its port when it names one), never to any host that asks
 * (a next link elsewhere gets no login). An interceptor refuses any method other than GET before it leaves, but a POST
 * to the page salesOrders (step 6, the tickets) or to the page shipAndInvoice (step 7, a ticket's invoice): the connector
 * writes nothing else to the ERP. Exists only with erp.connector=parafendri; its own bean name, so no other RestTemplate
 * is affected.
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
		RestTemplate template = new RestTemplate(ErpHttp.requestFactory(login(properties),
				properties.getConnectTimeoutSeconds(), properties.getReadTimeoutSeconds()));
		template.getInterceptors().add(new GetOnly());
		return template;
	}

	/** The NTLM login, for the host of base-url (and its port when it names one) only. */
	public static ErpHttp.Login login(ParafendriProperties properties) {
		URI base = URI.create(properties.getBaseUrl().trim());
		return ErpHttp.ntlm(properties.getUsername(), properties.getPassword(), properties.getDomain())
				.onlyFor(base.getHost(), base.getPort());
	}

	/** Refuses every request that is not a GET, before it is sent. */
	public static class GetOnly implements ClientHttpRequestInterceptor {

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			if (request.getMethod() != HttpMethod.GET && !postAllowed(request)) {
				throw new IllegalStateException("The parafendri connector only reads from the ERP (GET), apart from"
						+ " posting the tickets to " + SALES_ORDERS + " and their invoices to " + SHIP_AND_INVOICE + ": "
						+ request.getMethod() + " " + request.getURI() + " refused, nothing was sent.");
			}
			return execution.execute(request, body);
		}

		/**
		 * Step 6: a POST to the page salesOrders, step 7 to the page shipAndInvoice (the address ends with /salesOrders or
		 * /shipAndInvoice, no query), the only writes.
		 */
		static boolean postAllowed(HttpRequest request) {
			String path = request.getURI().getPath();
			return request.getMethod() == HttpMethod.POST && request.getURI().getQuery() == null && path != null
					&& (path.endsWith("/" + SALES_ORDERS) || path.endsWith("/" + SHIP_AND_INVOICE));
		}
	}

	/** Step 6: the sales orders page, the tickets posted with their lines (one of the connector's two writes). */
	public static final String SALES_ORDERS = "salesOrders";

	/** Step 7: the page that ships and invoices a sales order ({ orderNo }), the connector's other write. */
	public static final String SHIP_AND_INVOICE = "shipAndInvoice";
}
