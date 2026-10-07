package com.digithink.zsretail.erp.navpospages.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.digithink.zsretail.erp.navpospages.config.NavPosPagesConfig;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCollection;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;

/**
 * ERP catalogue, step 5: the three reads of the "POS pages", GET only, only the fields used ($select). The categories
 * and the items of the configured location follow @odata.nextLink to the end; the barcodes are read one page per call,
 * after an entry number, the caller asking again from the highest Entry_No received. An HTTP error, a timeout or no
 * answer throws {@link NavPosPagesReadException} naming the page and the status. No retry.
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class NavPosPagesRestClient implements NavPosPagesSource {

	static final String CATEGORY_FIELDS = "Code,Description,Parent_Category,Type";
	static final String ITEM_FIELDS = "Item_No,Variant_Code,Description,Unit_Price,Family,Subfamily";
	static final String BARCODE_FIELDS = "Item_No,Cross_Reference_No,Entry_No";

	private final RestTemplate restTemplate;
	private final NavPosPagesProperties properties;

	public NavPosPagesRestClient(@Qualifier(NavPosPagesConfig.REST_TEMPLATE) RestTemplate restTemplate,
			NavPosPagesProperties properties) {
		this.restTemplate = restTemplate;
		this.properties = properties;
	}

	/** Every row of the categories page. */
	@Override
	public List<NavPosCategoryRow> readCategories() {
		String page = properties.getPage().getCategories();
		URI uri = page(page).queryParam("$select", CATEGORY_FIELDS).build().encode().toUri();
		return readAll(page, uri, new ParameterizedTypeReference<NavPosCollection<NavPosCategoryRow>>() {
		});
	}

	/** Every row of the items page for the configured location (a quote in the code is doubled). */
	@Override
	public List<NavPosStockRow> readItems() {
		String page = properties.getPage().getItems();
		URI uri = page(page).queryParam("$filter", "Location_Code eq '" + quoted(properties.getLocationCode()) + "'")
				.queryParam("$select", ITEM_FIELDS).build().encode().toUri();
		return readAll(page, uri, new ParameterizedTypeReference<NavPosCollection<NavPosStockRow>>() {
		});
	}

	/** At most barcode-page-size rows with an Entry_No above entryNo, by Entry_No: one call, one page. */
	@Override
	public List<NavPosBarcodeRow> readBarcodesAfter(long entryNo) {
		String page = properties.getPage().getBarcodes();
		URI uri = page(page).queryParam("$filter", "Entry_No gt " + entryNo).queryParam("$orderby", "Entry_No")
				.queryParam("$top", properties.getBarcodePageSize()).queryParam("$select", BARCODE_FIELDS).build()
				.encode().toUri();
		NavPosCollection<NavPosBarcodeRow> body = get(page, uri,
				new ParameterizedTypeReference<NavPosCollection<NavPosBarcodeRow>>() {
				});
		return body == null || body.getValue() == null ? new ArrayList<>() : new ArrayList<>(body.getValue());
	}

	/**
	 * Step 6: every barcode of these items, $filter=(Item_No eq 'A' or Item_No eq 'B'), quotes doubled, next links
	 * followed. The caller sends a few item numbers per call.
	 */
	@Override
	public List<NavPosBarcodeRow> readBarcodesOfItems(List<String> itemNos) {
		if (itemNos == null || itemNos.isEmpty()) {
			return new ArrayList<>();
		}
		String page = properties.getPage().getBarcodes();
		StringBuilder filter = new StringBuilder();
		for (String itemNo : itemNos) {
			filter.append(filter.length() == 0 ? "" : " or ").append("Item_No eq '").append(quoted(itemNo)).append("'");
		}
		URI uri = page(page).queryParam("$filter", filter.toString()).queryParam("$select", BARCODE_FIELDS).build()
				.encode().toUri();
		return readAll(page, uri, new ParameterizedTypeReference<NavPosCollection<NavPosBarcodeRow>>() {
		});
	}

	@Override
	public String pageUrl(String page) {
		return page(page).build().toUriString();
	}

	/** base-url / Company('...') / page */
	private UriComponentsBuilder page(String page) {
		String base = properties.getBaseUrl().trim();
		String company = properties.getCompanyUrlSegment();
		return UriComponentsBuilder.fromHttpUrl(base.endsWith("/") ? base : base + "/")
				.path((company.isEmpty() ? "" : company + "/") + page.trim());
	}

	private <T> List<T> readAll(String page, URI first, ParameterizedTypeReference<NavPosCollection<T>> type) {
		List<T> rows = new ArrayList<>();
		URI next = first;
		while (next != null) {
			NavPosCollection<T> body = get(page, next, type);
			if (body == null || body.getValue() == null) {
				break;
			}
			rows.addAll(body.getValue());
			String link = body.getNextLink();
			next = link == null || link.trim().isEmpty() ? null : URI.create(link.trim());
		}
		return rows;
	}

	private <T> NavPosCollection<T> get(String page, URI uri, ParameterizedTypeReference<NavPosCollection<T>> type) {
		try {
			ResponseEntity<NavPosCollection<T>> answer = restTemplate.exchange(uri, HttpMethod.GET, null, type);
			return answer.getBody();
		} catch (HttpStatusCodeException e) {
			throw new NavPosPagesReadException(page, "navpospages: reading the page " + page + " failed: HTTP "
					+ e.getRawStatusCode() + " " + e.getStatusText() + " (" + uri + ")", e);
		} catch (ResourceAccessException e) {
			throw new NavPosPagesReadException(page, "navpospages: reading the page " + page
					+ " failed: no answer or timeout (" + e.getMessage() + ")", e);
		} catch (RestClientException e) {
			throw new NavPosPagesReadException(page, "navpospages: reading the page " + page + " failed: "
					+ e.getMessage(), e);
		}
	}

	static String quoted(String value) {
		return value == null ? "" : value.trim().replace("'", "''");
	}
}
