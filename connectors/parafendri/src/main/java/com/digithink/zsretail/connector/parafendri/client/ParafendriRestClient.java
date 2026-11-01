package com.digithink.zsretail.connector.parafendri.client;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.connector.parafendri.config.ParafendriConfig;
import com.digithink.zsretail.connector.parafendri.config.ParafendriProperties;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriBarcodeRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCollection;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCompanyRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriItemCategoryRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriLocationRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriSalesOrder;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriSalesOrderRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriShipAndInvoiceRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriStockPointRow;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ParaFendri, step 0: the reads of the custom API, GET only, following @odata.nextLink to the end. Step 0 reads one
 * page, the companies of the API (api-path/companies), and checks that company-id is among them: the connection test.
 * Step 1: the locations of the company (the points de vente) and the check of one code. Step 2: the tree itemCategories. An HTTP error, a timeout or no
 * answer throws {@link ParafendriReadException} naming the page and the status. No retry. Called once after the start
 * by {@code ParafendriConnectionCheck}, on its own thread.
 */
@Component
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
public class ParafendriRestClient {

	/** The page of the companies of the API, under api-path (not under a company). */
	static final String COMPANIES = "companies";
	private static final ParameterizedTypeReference<ParafendriCollection<ParafendriCompanyRow>> COMPANY_ROWS =
			new ParameterizedTypeReference<ParafendriCollection<ParafendriCompanyRow>>() {
			};
	/** Step 1: the page of the points de vente, under companies(company-id). */
	static final String LOCATIONS = "locations";
	static final String LOCATION_FIELDS = "code,name,lastModifiedDateTime";
	private static final ParameterizedTypeReference<ParafendriCollection<ParafendriLocationRow>> LOCATION_ROWS =
			new ParameterizedTypeReference<ParafendriCollection<ParafendriLocationRow>>() {
			};
	private static final ParameterizedTypeReference<ParafendriCollection<ParafendriSalesOrderRow>> SALES_ORDER_ROWS =
			new ParameterizedTypeReference<ParafendriCollection<ParafendriSalesOrderRow>>() {
			};
	private static final ParameterizedTypeReference<ParafendriCollection<ParafendriShipAndInvoiceRow>> SHIP_AND_INVOICE_ROWS =
			new ParameterizedTypeReference<ParafendriCollection<ParafendriShipAndInvoiceRow>>() {
			};
	/** Step 4: the barcodes of the company, under companies(company-id). */
	static final String BARCODES = "barCodes";
	private static final ParameterizedTypeReference<ParafendriCollection<ParafendriBarcodeRow>> BARCODE_ROWS =
			new ParameterizedTypeReference<ParafendriCollection<ParafendriBarcodeRow>>() {
			};
	/** Step 3: the items of each point de vente, under companies(company-id). */
	static final String STOCK_POINTS = "stockPoints";
	private static final ParameterizedTypeReference<ParafendriCollection<ParafendriStockPointRow>> STOCK_POINT_ROWS =
			new ParameterizedTypeReference<ParafendriCollection<ParafendriStockPointRow>>() {
			};
	/** Step 2: the tree of catégories, familles, sous-familles and sections, under companies(company-id). */
	static final String ITEM_CATEGORIES = "itemCategories";
	private static final ParameterizedTypeReference<ParafendriCollection<ParafendriItemCategoryRow>> ITEM_CATEGORY_ROWS =
			new ParameterizedTypeReference<ParafendriCollection<ParafendriItemCategoryRow>>() {
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

	/**
	 * Step 1: every location of the company, GET companies(company-id)/locations?$select=code,name,lastModifiedDateTime,
	 * next links followed.
	 */
	public List<ParafendriLocationRow> readLocations() {
		URI uri = UriComponentsBuilder.fromHttpUrl(properties.companyAddress() + LOCATIONS)
				.queryParam("$select", LOCATION_FIELDS).build().encode().toUri();
		return readAll(LOCATIONS, uri, LOCATION_ROWS);
	}

	/**
	 * Step 2: every row of the tree of the company, GET companies(company-id)/itemCategories, next links followed (no
	 * $select: the fields not used are ignored).
	 */
	public List<ParafendriItemCategoryRow> readItemCategories() {
		return readAll(ITEM_CATEGORIES, URI.create(itemCategoriesAddress()), ITEM_CATEGORY_ROWS);
	}

	/** Step 2: the address of the tree, for the communications log. */
	public String itemCategoriesAddress() {
		return properties.companyAddress() + ITEM_CATEGORIES;
	}

	/**
	 * Step 3: the rows of one point de vente, from a checkpoint when there is one (see {@link #stockPointsUri}), next
	 * links followed.
	 */
	public List<ParafendriStockPointRow> readStockPoints(String locationCode, OffsetDateTime since) {
		return readAll(STOCK_POINTS, stockPointsUri(locationCode, since), STOCK_POINT_ROWS);
	}

	/** Step 3: the first page only of the rows of one point de vente, its next link with it (the live test). */
	public ParafendriCollection<ParafendriStockPointRow> readStockPointsFirstPage(String locationCode,
			OffsetDateTime since) {
		return get(STOCK_POINTS, stockPointsUri(locationCode, since), STOCK_POINT_ROWS);
	}

	/**
	 * Step 3: companies(company-id)/stockPoints?$filter=locationCode eq 'X' (a quote doubled), and from a checkpoint
	 * "and lastModifiedDateTime ge" its time in UTC: ge as ZEN's, so that a row Business Central wrote in the same tick of
	 * its SQL datetime (1/300 s) as the newest row read before is never missed (the few rows of that time are read again;
	 * the import is the same). No $select: the fields not used are ignored.
	 */
	public URI stockPointsUri(String locationCode, OffsetDateTime since) {
		return UriComponentsBuilder.fromHttpUrl(properties.companyAddress() + STOCK_POINTS)
				.queryParam("$filter", stockPointsFilter(locationCode, since)).build().encode().toUri();
	}

	/** locationCode eq 'X' [and lastModifiedDateTime ge 2026-10-23T17:13:20.627Z]: the time in UTC, no + in the address. */
	public static String stockPointsFilter(String locationCode, OffsetDateTime since) {
		String filter = "locationCode eq '" + quoted(locationCode) + "'";
		return since == null ? filter : filter + " and lastModifiedDateTime ge " + utc(since);
	}

	/**
	 * Step 4: the barcodes of the company (company-wide, no location), every row without a checkpoint, else the rows with
	 * lastModifiedDateTime ge its time in UTC (ge as stockPoints), next links followed.
	 */
	public List<ParafendriBarcodeRow> readBarcodes(OffsetDateTime since) {
		return readAll(BARCODES, barcodesUri(since), BARCODE_ROWS);
	}

	/** Step 4: the first page only of {@link #readBarcodes}, its next link with it (the live test). */
	public ParafendriCollection<ParafendriBarcodeRow> readBarcodesFirstPage(OffsetDateTime since) {
		return get(BARCODES, barcodesUri(since), BARCODE_ROWS);
	}

	/** Step 4: companies(company-id)/barCodes[?$filter=lastModifiedDateTime ge <time in UTC>]. */
	public URI barcodesUri(OffsetDateTime since) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(properties.companyAddress() + BARCODES);
		if (since != null) {
			builder.queryParam("$filter", "lastModifiedDateTime ge " + utc(since));
		}
		return builder.build().encode().toUri();
	}

	/**
	 * Step 4: every barcode of these items, $filter=itemNo eq 'A' or itemNo eq 'B' (a quote doubled), {@link
	 * #ITEMS_PER_BARCODE_READ} items per GET, next links followed.
	 */
	public List<ParafendriBarcodeRow> readBarcodesOfItems(List<String> itemNos) {
		List<ParafendriBarcodeRow> rows = new ArrayList<>();
		if (itemNos == null) {
			return rows;
		}
		for (int from = 0; from < itemNos.size(); from += ITEMS_PER_BARCODE_READ) {
			List<String> batch = itemNos.subList(from, Math.min(from + ITEMS_PER_BARCODE_READ, itemNos.size()));
			URI uri = UriComponentsBuilder.fromHttpUrl(properties.companyAddress() + BARCODES)
					.queryParam("$filter", itemsFilter(batch)).build().encode().toUri();
			rows.addAll(readAll(BARCODES, uri, BARCODE_ROWS));
		}
		return rows;
	}

	/** itemNo eq 'A' or itemNo eq 'B' */
	static String itemsFilter(List<String> itemNos) {
		StringBuilder filter = new StringBuilder();
		for (String itemNo : itemNos) {
			filter.append(filter.length() == 0 ? "" : " or ").append("itemNo eq '").append(quoted(itemNo)).append("'");
		}
		return filter.toString();
	}

	// ─── Step 6: the tickets, posted as sales orders ────────────

	/** Step 6: base-url / api-path / companies(company-id) / salesOrders. */
	public String salesOrdersAddress() {
		return properties.companyAddress() + ParafendriConfig.SALES_ORDERS;
	}

	/** Step 6: the sales orders of one ticket, GET salesOrders?$filter=ticketNo eq 'X' (a quote doubled). */
	public List<ParafendriSalesOrderRow> readSalesOrdersOfTicket(String ticketNo) {
		URI uri = UriComponentsBuilder.fromHttpUrl(salesOrdersAddress())
				.queryParam("$filter", "ticketNo eq '" + quoted(ticketNo) + "'").build().encode().toUri();
		return readAll(ParafendriConfig.SALES_ORDERS, uri, SALES_ORDER_ROWS);
	}

	/**
	 * Step 6: one ticket posted, its lines in the same POST (deep insert); Business Central's answer, the header with its
	 * number and orderStatus. A refusal throws {@link ParafendriReadException} naming the status and Business Central's
	 * own error text; no answer (a timeout) too: the ticket may have been created, which the next run's lookup finds.
	 */
	public ParafendriSalesOrderRow postSalesOrder(ParafendriSalesOrder order) {
		String page = ParafendriConfig.SALES_ORDERS;
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
		try {
			return restTemplate.exchange(URI.create(salesOrdersAddress()), HttpMethod.POST,
					new HttpEntity<>(order, headers), ParafendriSalesOrderRow.class).getBody();
		} catch (HttpStatusCodeException e) {
			throw new ParafendriReadException(page, e.getRawStatusCode(), "parafendri: posting the ticket "
					+ order.getTicketNo() + " to " + page + " failed: HTTP " + e.getRawStatusCode() + " "
					+ e.getStatusText() + ": " + errorText(e.getResponseBodyAsString()), e);
		} catch (ResourceAccessException e) {
			throw new ParafendriReadException(page, null, "parafendri: posting the ticket " + order.getTicketNo()
					+ " to " + page + " failed: no answer or timeout (" + e.getMessage() + "); the next run looks it up"
					+ " before posting again", e);
		} catch (RestClientException e) {
			throw new ParafendriReadException(page, null, "parafendri: posting the ticket " + order.getTicketNo()
					+ " to " + page + " failed: " + e.getMessage(), e);
		}
	}

	/** Business Central's error {"error":{"code":"..","message":".."}} as "code: message"; else the body (cut). */
	static String errorText(String body) {
		String text = body == null ? "" : body.trim();
		try {
			JsonNode error = new ObjectMapper().readTree(text).path("error");
			if (error.hasNonNull("message")) {
				String code = error.path("code").asText("");
				return (code.isEmpty() ? "" : code + ": ") + error.get("message").asText();
			}
		} catch (Exception e) {
			// not JSON: the body as it is
		}
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}

	// ─── Step 7: a ticket's invoice, shipAndInvoice ────────────

	/** Step 7: base-url / api-path / companies(company-id) / shipAndInvoice. */
	public String shipAndInvoiceAddress() {
		return properties.companyAddress() + ParafendriConfig.SHIP_AND_INVOICE;
	}

	/** Step 7: the rows of one sales order, GET shipAndInvoice?$filter=orderNo eq 'X' (its key; a quote doubled). */
	public List<ParafendriShipAndInvoiceRow> readShipAndInvoice(String orderNo) {
		return readAll(ParafendriConfig.SHIP_AND_INVOICE, shipAndInvoiceUri(orderNo), SHIP_AND_INVOICE_ROWS);
	}

	/** Step 7: the address of the lookup of one sales order's invoice. */
	public URI shipAndInvoiceUri(String orderNo) {
		return UriComponentsBuilder.fromHttpUrl(shipAndInvoiceAddress())
				.queryParam("$filter", "orderNo eq '" + quoted(orderNo) + "'").build().encode().toUri();
	}

	/**
	 * Step 7: one sales order shipped and invoiced, POST { "orderNo": "X" } (Business Central has no field for the
	 * customer's fiscal registration or name there); its answer, the row with invoiceNo and invoicePdfBase64. A refusal
	 * throws {@link ParafendriReadException} naming the status and Business Central's own error text; no answer (a
	 * timeout) too: the invoice may have been made, which the next run's lookup finds.
	 */
	public ParafendriShipAndInvoiceRow postShipAndInvoice(String orderNo) {
		String page = ParafendriConfig.SHIP_AND_INVOICE;
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
		try {
			return restTemplate.exchange(URI.create(shipAndInvoiceAddress()), HttpMethod.POST,
					new HttpEntity<>(shipAndInvoiceRequest(orderNo), headers), ParafendriShipAndInvoiceRow.class)
					.getBody();
		} catch (HttpStatusCodeException e) {
			throw new ParafendriReadException(page, e.getRawStatusCode(), "parafendri: asking the invoice of sales order "
					+ orderNo + " from " + page + " failed: HTTP " + e.getRawStatusCode() + " " + e.getStatusText() + ": "
					+ errorText(e.getResponseBodyAsString()), e);
		} catch (ResourceAccessException e) {
			throw new ParafendriReadException(page, null, "parafendri: asking the invoice of sales order " + orderNo
					+ " from " + page + " failed: no answer or timeout (" + e.getMessage() + "); the next run looks it up"
					+ " before asking again", e);
		} catch (RestClientException e) {
			throw new ParafendriReadException(page, null, "parafendri: asking the invoice of sales order " + orderNo
					+ " from " + page + " failed: " + e.getMessage(), e);
		}
	}

	/** Step 7: the body of the POST, { "orderNo": "X" }. */
	public static Map<String, String> shipAndInvoiceRequest(String orderNo) {
		return Collections.singletonMap("orderNo", orderNo);
	}

	/**
	 * Step 6: the point de vente's customer in Business Central, customerNo of one stockPoints row of the location
	 * (GET stockPoints?$filter=locationCode eq 'X'&$top=1); null when the location has no row or no customerNo.
	 */
	public String readCustomerNo(String locationCode) {
		URI uri = UriComponentsBuilder.fromHttpUrl(properties.companyAddress() + STOCK_POINTS)
				.queryParam("$filter", stockPointsFilter(locationCode, null)).queryParam("$top", 1).build().encode()
				.toUri();
		ParafendriCollection<ParafendriStockPointRow> page = get(STOCK_POINTS, uri, STOCK_POINT_ROWS);
		if (page == null || page.getValue() == null || page.getValue().isEmpty()) {
			return null;
		}
		String customerNo = page.getValue().get(0).getCustomerNo();
		return customerNo == null || customerNo.trim().isEmpty() ? null : customerNo.trim();
	}

	/** Step 4: item numbers per GET of their barcodes (a short address). */
	static final int ITEMS_PER_BARCODE_READ = 20;

	/** The time in UTC, ISO (2026-10-25T08:15:42.317Z: no + in the address). */
	static String utc(OffsetDateTime time) {
		return time.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	static String quoted(String value) {
		return value == null ? "" : value.trim().replace("'", "''");
	}

	/** Step 1: true when the code (trimmed, its case ignored) is one of the locations of the company. */
	public boolean checkLocation(String code) {
		return hasLocation(readLocations(), code);
	}

	static boolean hasLocation(List<ParafendriLocationRow> rows, String code) {
		String wanted = code == null ? "" : code.trim();
		if (wanted.isEmpty()) {
			return false;
		}
		for (ParafendriLocationRow row : rows) {
			if (row.getCode() != null && row.getCode().trim().equalsIgnoreCase(wanted)) {
				return true;
			}
		}
		return false;
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
