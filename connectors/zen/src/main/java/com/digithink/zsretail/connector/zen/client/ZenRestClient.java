package com.digithink.zsretail.connector.zen.client;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
import com.digithink.zsretail.connector.zen.dto.ZenCompanyRow;
import com.digithink.zsretail.connector.zen.dto.ZenStockPointRow;
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

/**
 * Item variants (ZEN), step 8: the reads of ZEN's API, GET only, as the other NAV connectors. The tree and the rows of
 * one location follow @odata.nextLink to the end; the rows of a location are filtered by locationCode, and from a
 * checkpoint by lastModifiedDateTime ge (the time in UTC). No $select on these two pages: the fields not used are
 * ignored, and active and vat are read when the API gives them. Step 12a, ZEN's page barCodes: the barcodes are read
 * one page per call, after a position (lastModifiedDateTime in epoch milliseconds, {@link ZenMapper#position}), the
 * caller asking again from the highest position received; a full page is completed to the end of its last
 * millisecond, so that a page never ends in the middle of one; those of some items by itemNo and variantCode. An HTTP
 * error, a timeout or no answer throws {@link ZenReadException} naming the page and the status.
 * No retry. Step 10: every page is under companies(id) of the custom API; the id is erp.zen.company-id, or looked up once
 * by the company's name ({@link #companyId()}).
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenRestClient implements ZenSource {

	/** Step 10: the page of the companies of the API, read to find the id of company (the name). */
	static final String COMPANIES = "companies";
	/** Step 12a: the field of the barcodes' cursor. */
	static final String MODIFIED = "lastModifiedDateTime";
	private static final ParameterizedTypeReference<ZenCollection<ZenBarcodeRow>> BARCODES =
			new ParameterizedTypeReference<ZenCollection<ZenBarcodeRow>>() {
			};

	private final RestTemplate restTemplate;
	private final ZenProperties properties;
	/** Step 10: the company's id, from company-id or looked up once by name, kept until the application stops. */
	private volatile String companyId;

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

	/**
	 * Step 12a: one page by lastModifiedDateTime, $top barcode-page-size, the rows above the position
	 * ({@link #barcodesAfterFilter}). A full page may end in the middle of a time whose other rows are on the next
	 * page, past the cursor: then every row of its last time is read too ({@link #barcodesAtFilter}, the next links
	 * followed), those already on the page (same id) once.
	 */
	@Override
	public List<ZenBarcodeRow> readBarcodesAfter(long position) {
		String page = properties.getPage().getBarcodes();
		int size = properties.getBarcodePageSize();
		UriComponentsBuilder first = page(page);
		String filter = barcodesAfterFilter(position);
		if (filter != null) {
			first.queryParam("$filter", filter);
		}
		URI uri = first.queryParam("$orderby", MODIFIED).queryParam("$top", size)
				.queryParam("$select", ZenBarcodeRow.FIELDS).build().encode().toUri();
		ZenCollection<ZenBarcodeRow> body = get(page, uri, BARCODES);
		List<ZenBarcodeRow> rows = body == null || body.getValue() == null ? new ArrayList<>()
				: new ArrayList<>(body.getValue());
		if (rows.size() < size) {
			return rows;
		}
		long last = rows.stream().mapToLong(row -> ZenMapper.position(row.getLastModifiedDateTime())).max()
				.getAsLong();
		URI rest = page(page).queryParam("$filter", barcodesAtFilter(last)).queryParam("$select", ZenBarcodeRow.FIELDS)
				.build().encode().toUri();
		Set<String> seen = rows.stream().map(ZenRestClient::rowKey).collect(Collectors.toCollection(HashSet::new));
		for (ZenBarcodeRow row : readAll(page, rest, BARCODES)) {
			if (seen.add(rowKey(row))) {
				rows.add(row);
			}
		}
		return rows;
	}

	/**
	 * The rows above a position: none at 0 or below (every row, NAV's empty date included); else lastModifiedDateTime gt
	 * the position itself, in UTC (2026-10-11T16:53:37.150Z). Never the position + 1 ms: NAV keeps these dates in SQL
	 * datetime (ticks of 1/300 s, .000 .003 .007, shown to the millisecond), and SQL rounds a time it is given to a
	 * tick: ge .208 is ge .207, the rows at the cursor came back and the engine's guard stopped the run (step 12a
	 * follow-up). The position is a time NAV gave, so gt and eq on it land on its own tick.
	 */
	public static String barcodesAfterFilter(long position) {
		return position <= 0 ? null : MODIFIED + " gt " + instant(position);
	}

	/**
	 * Every row of one position: lastModifiedDateTime eq it; for {@link ZenMapper#EMPTY_DATE_POSITION}, every row
	 * before 1970 (NAV's empty date 0001-01-01 included).
	 */
	public static String barcodesAtFilter(long position) {
		if (position <= ZenMapper.EMPTY_DATE_POSITION) {
			return MODIFIED + " lt " + instant(0);
		}
		return MODIFIED + " eq " + instant(position);
	}

	private static String instant(long millis) {
		return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis));
	}

	/** The row's id; without one, its values (never the case in the API: two such rows are then one). */
	private static String rowKey(ZenBarcodeRow row) {
		String id = row.getId() == null ? "" : row.getId().trim();
		return !id.isEmpty() ? id
				: "|" + row.getItemNo() + "|" + row.getVariantCode() + "|" + row.getBarCode() + "|"
						+ row.getLastModifiedDateTime();
	}

	@Override
	public List<ZenBarcodeRow> readBarcodesOfItems(List<String> itemCodes) {
		if (itemCodes == null || itemCodes.isEmpty()) {
			return new ArrayList<>();
		}
		String page = properties.getPage().getBarcodes();
		URI uri = page(page).queryParam("$filter", barcodesOfItemsFilter(itemCodes))
				.queryParam("$select", ZenBarcodeRow.FIELDS).build().encode().toUri();
		return readAll(page, uri, BARCODES);
	}

	/**
	 * (itemNo eq 'A' and variantCode eq 'V') or ...: an item code itemNo~variantCode cut at its first ~, a code without
	 * ~ is an item without a variant (variantCode eq ''); quotes doubled.
	 */
	public static String barcodesOfItemsFilter(List<String> itemCodes) {
		StringBuilder filter = new StringBuilder();
		for (String code : itemCodes) {
			String text = code == null ? "" : code.trim();
			int cut = text.indexOf(ZenMapper.SEPARATOR);
			String itemNo = cut < 0 ? text : text.substring(0, cut);
			String variant = cut < 0 ? "" : text.substring(cut + ZenMapper.SEPARATOR.length());
			filter.append(filter.length() == 0 ? "" : " or ").append("(itemNo eq '").append(quoted(itemNo))
					.append("' and variantCode eq '").append(quoted(variant)).append("')");
		}
		return filter.toString();
	}

	@Override
	public String pageUrl(String page) {
		return page(page).build().toUriString();
	}

	/** A page under companies(id) (the id found first), or a whole address as it is (no lookup). */
	private UriComponentsBuilder page(String page) {
		String name = page == null ? "" : page.trim();
		boolean whole = name.startsWith("http://") || name.startsWith("https://");
		return UriComponentsBuilder.fromHttpUrl(properties.pageAddress(page, whole ? null : companyId()));
	}

	/**
	 * Step 10: the company's id in the API: company-id when set (no call); else looked up once by company (the name),
	 * GET api-path/companies?$filter=name eq '<name>' (a quote doubled, the address encoded as for Happyness), then kept.
	 * No company of that name: {@link ZenReadException} naming it.
	 */
	public String companyId() {
		String known = companyId;
		if (known != null) {
			return known;
		}
		String configured = properties.getCompanyId() == null ? "" : properties.getCompanyId().trim();
		if (!configured.isEmpty()) {
			companyId = configured;
			return configured;
		}
		String name = properties.getCompany() == null ? "" : properties.getCompany().trim();
		URI uri = UriComponentsBuilder.fromHttpUrl(properties.apiAddress() + COMPANIES)
				.queryParam("$filter", "name eq '" + quoted(name) + "'").build().encode().toUri();
		ZenCollection<ZenCompanyRow> body = get(COMPANIES, uri, new ParameterizedTypeReference<ZenCollection<ZenCompanyRow>>() {
		});
		List<ZenCompanyRow> rows = body == null || body.getValue() == null ? new ArrayList<>() : body.getValue();
		if (rows.isEmpty() || rows.get(0).getId() == null || rows.get(0).getId().trim().isEmpty()) {
			throw new ZenReadException(COMPANIES, "zen: no company named '" + name + "' in the API (" + uri
					+ "): check erp.zen.company, or set erp.zen.company-id", null);
		}
		companyId = rows.get(0).getId().trim();
		return companyId;
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
