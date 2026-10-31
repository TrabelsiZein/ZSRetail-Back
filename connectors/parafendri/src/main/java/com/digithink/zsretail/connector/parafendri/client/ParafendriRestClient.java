package com.digithink.zsretail.connector.parafendri.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.connector.parafendri.config.ParafendriConfig;
import com.digithink.zsretail.connector.parafendri.config.ParafendriProperties;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCollection;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCompanyRow;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

/**
 * ParaFendri, step 0: the reads of the custom API, GET only, following @odata.nextLink to the end. Step 0 reads one
 * page, the companies of the API (api-path/companies), and checks that company-id is among them: the connection test.
 * An HTTP error, a timeout or no answer throws {@link ParafendriReadException} naming the page and the status. No
 * retry. Nothing calls it at startup.
 */
@Component
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
public class ParafendriRestClient {

	/** The page of the companies of the API, under api-path (not under a company). */
	static final String COMPANIES = "companies";
	private static final ParameterizedTypeReference<ParafendriCollection<ParafendriCompanyRow>> COMPANY_ROWS =
			new ParameterizedTypeReference<ParafendriCollection<ParafendriCompanyRow>>() {
			};

	private final RestTemplate restTemplate;
	private final ParafendriProperties properties;

	public ParafendriRestClient(@Qualifier(ParafendriConfig.REST_TEMPLATE) RestTemplate restTemplate,
			ParafendriProperties properties) {
		this.restTemplate = restTemplate;
		this.properties = properties;
	}

	/** Every company of the API: GET base-url/api-path/companies. */
	public List<ParafendriCompanyRow> readCompanies() {
		return readAll(COMPANIES, URI.create(properties.apiAddress() + COMPANIES), COMPANY_ROWS);
	}

	/**
	 * The connection test: the companies read, and the row of company-id (its case ignored). None of that id:
	 * {@link ParafendriReadException} naming it and the companies found.
	 */
	public ParafendriCompanyRow checkCompany() {
		String wanted = properties.getCompanyId() == null ? "" : properties.getCompanyId().trim();
		List<ParafendriCompanyRow> rows = readCompanies();
		for (ParafendriCompanyRow row : rows) {
			if (row.getId() != null && row.getId().trim().equalsIgnoreCase(wanted)) {
				return row;
			}
		}
		String found = rows.isEmpty() ? "none"
				: rows.stream().map(row -> row.getId() + " (" + row.getName() + ")").collect(Collectors.joining(", "));
		throw new ParafendriReadException(COMPANIES, null, "parafendri: no company with id " + wanted + " in the API ("
				+ properties.apiAddress() + COMPANIES + "), companies found: " + found + ": check "
				+ ParafendriProperties.PREFIX + ".company-id", null);
	}

	private <T> List<T> readAll(String page, URI first, ParameterizedTypeReference<ParafendriCollection<T>> type) {
		List<T> rows = new ArrayList<>();
		URI next = first;
		while (next != null) {
			ParafendriCollection<T> body = get(page, next, type);
			if (body == null || body.getValue() == null) {
				break;
			}
			rows.addAll(body.getValue());
			String link = body.getNextLink();
			next = link == null || link.trim().isEmpty() ? null : URI.create(link.trim());
		}
		return rows;
	}

	private <T> ParafendriCollection<T> get(String page, URI uri,
			ParameterizedTypeReference<ParafendriCollection<T>> type) {
		try {
			return restTemplate.exchange(uri, HttpMethod.GET, null, type).getBody();
		} catch (HttpStatusCodeException e) {
			throw new ParafendriReadException(page, e.getRawStatusCode(), "parafendri: reading the page " + page
					+ " failed: HTTP " + e.getRawStatusCode() + " " + e.getStatusText() + " (" + uri + ")", e);
		} catch (ResourceAccessException e) {
			throw new ParafendriReadException(page, null, "parafendri: reading the page " + page
					+ " failed: no answer or timeout (" + e.getMessage() + ")", e);
		} catch (RestClientException e) {
			throw new ParafendriReadException(page, null, "parafendri: reading the page " + page + " failed: "
					+ e.getMessage(), e);
		}
	}
}
