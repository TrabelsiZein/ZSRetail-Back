package com.digithink.zsretail.connector.zen.client;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
import com.digithink.zsretail.connector.zen.dto.ZenInvoiceLineRow;
import com.digithink.zsretail.connector.zen.dto.ZenInvoiceRow;
import com.digithink.zsretail.connector.zen.dto.ZenStockPointRow;
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;
import com.digithink.zsretail.connector.zen.mapper.ZenOrderFields;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * by the company's name ({@link #companyId()}). Step 9: the supply invoices are on ZEN's second custom API,
 * api/sat/franchise/v1.0, page salesInvoices under the same companies(id) ({@link ZenInvoiceSource}, the same client
 * and credentials), read by number per year prefix, only those of the stores' customers.
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenRestClient implements ZenSource, ZenInvoiceSource {

	/** Step 10: the page of the companies of the API, read to find the id of company (the name). */
	static final String COMPANIES = "companies";
	/** Step 12a: the field of the barcodes' cursor. */
	static final String MODIFIED = "lastModifiedDateTime";
	private static final ParameterizedTypeReference<ZenCollection<ZenBarcodeRow>> BARCODES =
			new ParameterizedTypeReference<ZenCollection<ZenBarcodeRow>>() {
			};
	private static final ParameterizedTypeReference<ZenCollection<ZenInvoiceRow>> INVOICES =
			new ParameterizedTypeReference<ZenCollection<ZenInvoiceRow>>() {
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

	// ─── Step 9: the invoices (ZEN's franchise API) ──────────────

	/** Invoice numbers per GET when they are read again by number (a short URL), as Happyness. */
	static final int NUMBERS_PER_READ = 20;

	/**
	 * {@link #invoicesAfterFilter}, $orderby=number, $top=invoices.page-size, the next links followed up to page-size,
	 * the lines expanded with their own $select. No $select on the header: cancelled and corrective are read once the
	 * page gives them, and a field named there that the page does not have would fail the read (HTTP 400).
	 */
	@Override
	public List<ZenInvoiceRow> readInvoicesAfter(String yearPrefix, String afterNumber,
			Collection<String> customerNumbers) {
		if (customerNumbers == null || customerNumbers.isEmpty()) {
			return new ArrayList<>();
		}
		return readInvoices(invoicesAfterFilter(yearPrefix, afterNumber, customerNumbers),
				properties.getInvoices().getPageSize());
	}

	/** $filter=number eq '..' or number eq '..', {@link #NUMBERS_PER_READ} numbers per GET, the lines of the read. */
	@Override
	public List<ZenInvoiceRow> readInvoicesByNumbers(List<String> numbers) {
		List<ZenInvoiceRow> rows = new ArrayList<>();
		if (numbers == null) {
			return rows;
		}
		for (int from = 0; from < numbers.size(); from += NUMBERS_PER_READ) {
			List<String> some = numbers.subList(from, Math.min(from + NUMBERS_PER_READ, numbers.size()));
			rows.addAll(readInvoices(numbersFilter(some), some.size()));
		}
		return rows;
	}

	/** One GET: $filter={@link #firstInvoiceFilter}, $orderby=number, $top=1, $select=number. */
	@Override
	public String readFirstInvoiceNumber(String numberPrefix, Collection<String> customerNumbers) {
		if (customerNumbers == null || customerNumbers.isEmpty()) {
			return null;
		}
		String page = properties.getInvoices().getPage().trim();
		URI uri = invoicesPage().queryParam("$filter", firstInvoiceFilter(numberPrefix, customerNumbers))
				.queryParam("$orderby", ZenInvoiceRow.NO).queryParam("$top", 1).queryParam("$select", ZenInvoiceRow.NO)
				.build().encode().toUri();
		ZenCollection<ZenInvoiceRow> body = get(page, uri, INVOICES);
		if (body == null || body.getValue() == null || body.getValue().isEmpty()) {
			return null;
		}
		String number = body.getValue().get(0).getNumber();
		return number.isEmpty() ? null : number;
	}

	@Override
	public String invoicesPageUrl() {
		return invoicesPage().build().toUriString();
	}

	/**
	 * startswith(number,'FVE26') [and number gt 'FVE2600001'] and ({@link #customersFilter}): only startswith on a first
	 * read; number gt alone would also give the later years (the numbers are compared as text). Quotes doubled.
	 */
	public static String invoicesAfterFilter(String yearPrefix, String afterNumber, Collection<String> customerNumbers) {
		return "startswith(" + ZenInvoiceRow.NO + ",'" + quoted(yearPrefix) + "')"
				+ (afterNumber == null || afterNumber.trim().isEmpty() ? ""
						: " and " + ZenInvoiceRow.NO + " gt '" + quoted(afterNumber) + "'")
				+ " and " + customersFilter(customerNumbers);
	}

	/** startswith(number,'FVE') and ({@link #customersFilter}). */
	public static String firstInvoiceFilter(String numberPrefix, Collection<String> customerNumbers) {
		return "startswith(" + ZenInvoiceRow.NO + ",'" + quoted(numberPrefix) + "') and " + customersFilter(customerNumbers);
	}

	/**
	 * (sellToCustomerNo eq 'A' or sellToCustomerNo eq 'B'), in brackets (and binds before or): each number trimmed,
	 * in upper case (a NAV customer number is a code, upper case; the head office matches a store at any case), once, a
	 * blank one left out, quotes doubled.
	 */
	public static String customersFilter(Collection<String> customerNumbers) {
		Set<String> numbers = new LinkedHashSet<>();
		for (String number : customerNumbers == null ? new ArrayList<String>() : customerNumbers) {
			String text = number == null ? "" : number.trim().toUpperCase(Locale.ROOT);
			if (!text.isEmpty()) {
				numbers.add(text);
			}
		}
		StringBuilder filter = new StringBuilder();
		for (String number : numbers) {
			filter.append(filter.length() == 0 ? "" : " or ").append(ZenInvoiceRow.CUSTOMER_NO).append(" eq '")
					.append(quoted(number)).append("'");
		}
		return "(" + filter + ")";
	}

	/** number eq 'x' or number eq 'y', quotes doubled. */
	public static String numbersFilter(List<String> numbers) {
		StringBuilder filter = new StringBuilder();
		for (String number : numbers) {
			filter.append(filter.length() == 0 ? "" : " or ").append(ZenInvoiceRow.NO).append(" eq '")
					.append(quoted(number)).append("'");
		}
		return filter.toString();
	}

	/**
	 * The invoices of the filter, by number, at most max (the next links followed until then), their lines resolved. No
	 * $select on the header ({@link #readInvoicesAfter}).
	 */
	private List<ZenInvoiceRow> readInvoices(String filter, int max) {
		ZenProperties.Invoices settings = properties.getInvoices();
		String page = settings.getPage().trim();
		String expand = settings.getLinesExpand().trim();
		URI uri = invoicesPage().queryParam("$filter", filter).queryParam("$orderby", ZenInvoiceRow.NO)
				.queryParam("$top", max).queryParam("$expand", expand + "($select=" + ZenInvoiceLineRow.FIELDS + ")")
				.build().encode().toUri();
		List<ZenInvoiceRow> rows = new ArrayList<>();
		URI next = uri;
		while (next != null && rows.size() < max) {
			ZenCollection<ZenInvoiceRow> body = get(page, next, INVOICES);
			if (body == null || body.getValue() == null) {
				break;
			}
			rows.addAll(body.getValue());
			String link = body.getNextLink();
			next = link == null || link.trim().isEmpty() ? null : URI.create(link.trim());
		}
		List<ZenInvoiceRow> kept = rows.size() > max ? new ArrayList<>(rows.subList(0, max)) : rows;
		for (ZenInvoiceRow row : kept) {
			row.resolveLines(expand);
		}
		return kept;
	}

	/**
	 * base-url / invoices.api-path / companies(id) / the invoices page (the id found first, as for the other pages), or a
	 * whole address as it is (no lookup).
	 */
	private UriComponentsBuilder invoicesPage() {
		return UriComponentsBuilder.fromHttpUrl(
				properties.invoicesPageAddress(properties.invoicesPageIsAddress() ? null : companyId()));
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

	// ─── 3.1.1: the store orders (variantstock.path, salesorders.path) ─

	private static final ObjectMapper JSON = new ObjectMapper();

	/**
	 * GET infoArticles?$filter=article eq '..' and client eq '..': NAV's stock of every variant of the item for the
	 * store's ERP customer (ZEN's page has no location).
	 */
	public List<ErpVariantStockDTO> readVariantStock(String itemNo, String customerNo) {
		String name = properties.getVariantstock().getPath();
		URI uri = page(name).queryParam("$filter", ZenOrderFields.stockFilter(itemNo, customerNo)).build().encode()
				.toUri();
		return ZenOrderFields.stockRows(json(name, uri, HttpMethod.GET, null), ZoneId.systemDefault());
	}

	/**
	 * GET salesOrders?$filter=externalDocumentNo eq '..'&amp;$select=orderNo: the number of NAV's order of this external
	 * document number; empty when there is none. A page that cannot answer it throws (the order is then not posted).
	 */
	public String findSalesOrderNo(String externalDocumentNo) {
		String name = properties.getSalesorders().getPath();
		URI uri = page(name).queryParam("$filter", ZenOrderFields.lookupFilter(externalDocumentNo))
				.queryParam("$select", ZenOrderFields.ORDER_NO).build().encode().toUri();
		return ZenOrderFields.foundOrderNo(json(name, uri, HttpMethod.GET, null));
	}

	/** POST salesOrders with the order's body (JSON): NAV's answer. */
	public JsonNode postSalesOrder(JsonNode body) {
		String name = properties.getSalesorders().getPath();
		return json(name, page(name).build().encode().toUri(), HttpMethod.POST, body);
	}

	/** The address of the sales orders page (the communications log; the interceptor allows a POST there only). */
	public String salesOrdersUrl() {
		return page(properties.getSalesorders().getPath()).build().toUriString();
	}

	/** One call with a JSON body or none, its JSON answer (null when empty); an error throws as a read does. */
	private JsonNode json(String page, URI uri, HttpMethod method, JsonNode body) {
		String what = method == HttpMethod.GET ? "reading the page " + page : "posting to the page " + page;
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
			HttpEntity<String> entity;
			if (body == null) {
				entity = new HttpEntity<>(headers);
			} else {
				headers.setContentType(MediaType.APPLICATION_JSON);
				entity = new HttpEntity<>(JSON.writeValueAsString(body), headers);
			}
			ResponseEntity<String> answer = restTemplate.exchange(uri, method, entity, String.class);
			String text = answer.getBody();
			return text == null || text.trim().isEmpty() ? null : JSON.readTree(text);
		} catch (HttpStatusCodeException e) {
			String detail = e.getResponseBodyAsString();
			throw new ZenReadException(page, "zen: " + what + " failed: HTTP " + e.getRawStatusCode() + " "
					+ e.getStatusText() + " (" + uri + ")" + (detail == null || detail.trim().isEmpty() ? ""
							: ": " + (detail.length() > 500 ? detail.substring(0, 500) : detail)), e);
		} catch (ResourceAccessException e) {
			throw new ZenReadException(page, "zen: " + what + " failed: no answer or timeout (" + e.getMessage() + ")", e);
		} catch (RestClientException | IOException e) {
			throw new ZenReadException(page, "zen: " + what + " failed: " + e.getMessage(), e);
		}
	}
}
