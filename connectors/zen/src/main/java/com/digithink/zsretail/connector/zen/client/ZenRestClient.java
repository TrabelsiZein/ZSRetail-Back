package com.digithink.zsretail.connector.zen.client;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.connector.zen.config.ZenConfig;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.dto.ZenBarcodeRow;
import com.digithink.zsretail.connector.zen.dto.ZenCategoryRow;
import com.digithink.zsretail.connector.zen.dto.ZenCollection;
import com.digithink.zsretail.connector.zen.dto.ZenStockPointRow;
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

/**
 * Item variants (ZEN), step 8: the reads of ZEN's API, GET only, as the other NAV connectors. The tree and the rows of
 * one location follow @odata.nextLink to the end; the rows of a location are filtered by locationCode, and from a
 * checkpoint by lastModifiedDateTime ge (the time in UTC). No $select on these two pages: the fields not used are
 * ignored, and active and vat are read when the API gives them. The barcodes are read one page per call, after an
 * entry number, the caller asking again from the highest Entry_No received; those of some items by Item_No and
 * Variant_Code. An HTTP error, a timeout or no answer throws {@link ZenReadException} naming the page and the status.
 * No retry.
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenRestClient implements ZenSource {

	private final RestTemplate restTemplate;
	private final ZenProperties properties;

	public ZenRestClient(@Qualifier(ZenConfig.REST_TEMPLATE) RestTemplate restTemplate, ZenProperties properties) {
		this.restTemplate = restTemplate;
		this.properties = properties;
	}

	@Override
	public List<ZenCategoryRow> readCategories() {
		String page = properties.getPage().getCategories();
		return readAll(page, page(page).build().encode().toUri(),
				new ParameterizedTypeReference<ZenCollection<ZenCategoryRow>>() {
				});
	}

	@Override
	public List<ZenStockPointRow> readStockPoints(String locationCode, OffsetDateTime since) {
		String page = properties.getPage().getStockPoints();
		URI uri = page(page).queryParam("$filter", stockPointsFilter(locationCode, since)).build().encode().toUri();
		return readAll(page, uri, new ParameterizedTypeReference<ZenCollection<ZenStockPointRow>>() {
		});
	}

	/**
	 * locationCode eq 'X' (a quote doubled), and from a checkpoint: and lastModifiedDateTime ge the time in UTC, ISO
	 * (2026-10-11T16:53:37.15Z: no + in the address).
	 */
	public static String stockPointsFilter(String locationCode, OffsetDateTime since) {
		String filter = "locationCode eq '" + quoted(locationCode) + "'";
		if (since == null) {
			return filter;
		}
		return filter + " and lastModifiedDateTime ge "
				+ since.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	@Override
	public List<ZenBarcodeRow> readBarcodesAfter(long entryNo) {
		String page = properties.getPage().getBarcodes();
		URI uri = page(page).queryParam("$filter", "Entry_No gt " + entryNo).queryParam("$orderby", "Entry_No")
				.queryParam("$top", properties.getBarcodePageSize()).queryParam("$select", ZenBarcodeRow.FIELDS).build()
				.encode().toUri();
		ZenCollection<ZenBarcodeRow> body = get(page, uri, new ParameterizedTypeReference<ZenCollection<ZenBarcodeRow>>() {
		});
		return body == null || body.getValue() == null ? new ArrayList<>() : new ArrayList<>(body.getValue());
	}

	@Override
	public List<ZenBarcodeRow> readBarcodesOfItems(List<String> itemCodes) {
		if (itemCodes == null || itemCodes.isEmpty()) {
			return new ArrayList<>();
		}
		String page = properties.getPage().getBarcodes();
		URI uri = page(page).queryParam("$filter", barcodesOfItemsFilter(itemCodes))
				.queryParam("$select", ZenBarcodeRow.FIELDS).build().encode().toUri();
		return readAll(page, uri, new ParameterizedTypeReference<ZenCollection<ZenBarcodeRow>>() {
		});
	}

	/**
	 * (Item_No eq 'A' and Variant_Code eq 'V') or ...: an item code itemNo~variantCode cut at its first ~, a code without
	 * ~ is an item without a variant (Variant_Code eq ''); quotes doubled.
	 */
	public static String barcodesOfItemsFilter(List<String> itemCodes) {
		StringBuilder filter = new StringBuilder();
		for (String code : itemCodes) {
			String text = code == null ? "" : code.trim();
			int cut = text.indexOf(ZenMapper.SEPARATOR);
			String itemNo = cut < 0 ? text : text.substring(0, cut);
			String variant = cut < 0 ? "" : text.substring(cut + ZenMapper.SEPARATOR.length());
			filter.append(filter.length() == 0 ? "" : " or ").append("(Item_No eq '").append(quoted(itemNo))
					.append("' and Variant_Code eq '").append(quoted(variant)).append("')");
		}
		return filter.toString();
	}

	@Override
	public String pageUrl(String page) {
		return page(page).build().toUriString();
	}

	private UriComponentsBuilder page(String page) {
		return UriComponentsBuilder.fromHttpUrl(properties.pageAddress(page));
	}

	private <T> List<T> readAll(String page, URI first, ParameterizedTypeReference<ZenCollection<T>> type) {
		List<T> rows = new ArrayList<>();
		URI next = first;
		while (next != null) {
			ZenCollection<T> body = get(page, next, type);
			if (body == null || body.getValue() == null) {
				break;
			}
			rows.addAll(body.getValue());
			String link = body.getNextLink();
			next = link == null || link.trim().isEmpty() ? null : URI.create(link.trim());
		}
		return rows;
	}

	private <T> ZenCollection<T> get(String page, URI uri, ParameterizedTypeReference<ZenCollection<T>> type) {
		try {
			ResponseEntity<ZenCollection<T>> answer = restTemplate.exchange(uri, HttpMethod.GET, null, type);
			return answer.getBody();
		} catch (HttpStatusCodeException e) {
			throw new ZenReadException(page, "zen: reading the page " + page + " failed: HTTP " + e.getRawStatusCode() + " "
					+ e.getStatusText() + " (" + uri + ")", e);
		} catch (ResourceAccessException e) {
			throw new ZenReadException(page, "zen: reading the page " + page + " failed: no answer or timeout ("
					+ e.getMessage() + ")", e);
		} catch (RestClientException e) {
			throw new ZenReadException(page, "zen: reading the page " + page + " failed: " + e.getMessage(), e);
		}
	}

	static String quoted(String value) {
		return value == null ? "" : value.trim().replace("'", "''");
	}
}
