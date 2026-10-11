package com.digithink.zsretail.erp.navpospages.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceLineRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;

import lombok.extern.log4j.Log4j2;

/**
 * ERP catalogue, step 5: the three reads of the "POS pages", GET only, only the fields used ($select). The categories
 * and the items of one stock point (Location_Code) follow @odata.nextLink to the end; the barcodes are read one page per call,
 * after an entry number, the caller asking again from the highest Entry_No received. An HTTP error, a timeout or no
 * answer throws {@link NavPosPagesReadException} naming the page and the status. No retry. Invoices from the ERP, step
 * (a): the invoices page read by number, see {@link #readInvoicesAfter}.
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
@Log4j2
public class NavPosPagesRestClient implements NavPosPagesSource {

	static final String CATEGORY_FIELDS = "Code,Description,Parent_Category,Type";
	static final String ITEM_FIELDS = "Item_No,Variant_Code,Description,Unit_Price,Family,Subfamily";
	static final String BARCODE_FIELDS = "Item_No,Cross_Reference_No,Entry_No";

	private final RestTemplate restTemplate;
	private final NavPosPagesProperties properties;

	/** False once the invoices page answered that it has no Prices_Including_VAT (until the next start). */
	private volatile boolean pricesIncludingVatOnPage = true;

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

	/** Every row of the items page for one stock point, its Location_Code (a quote in the code is doubled). */
	@Override
	public List<NavPosStockRow> readItems(String locationCode) {
		String page = properties.getPage().getItems();
		URI uri = page(page).queryParam("$filter", "Location_Code eq '" + quoted(locationCode) + "'")
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

	/**
	 * Invoices from the ERP, step (a): $filter=startswith(No,'FVV26') [and No gt 'afterNumber'], $orderby=No, $top and at
	 * most invoices.max-per-run invoices (next links followed until then), $select of the header fields used, the lines
	 * expanded with their own $select. Prices_Including_VAT is asked for until the page answers 400 that it has no such
	 * property: then the read is made again without it, and it is no longer asked for.
	 */
	@Override
	public List<NavPosInvoiceRow> readInvoicesAfter(String yearPrefix, String afterNumber) {
		return readInvoices("startswith(No,'" + quoted(yearPrefix) + "')"
				+ (afterNumber == null || afterNumber.trim().isEmpty() ? "" : " and No gt '" + quoted(afterNumber) + "'"));
	}

	/**
	 * 2.2.1: the invoices of these numbers (those a 2.2.0 head office held for a decimal quantity), read again: $filter=No
	 * eq '..' or No eq '..', {@link #NUMBERS_PER_READ} numbers per GET, the same fields and lines as
	 * {@link #readInvoicesAfter}.
	 */
	@Override
	public List<NavPosInvoiceRow> readInvoicesByNumbers(List<String> numbers) {
		List<NavPosInvoiceRow> rows = new ArrayList<>();
		if (numbers == null) {
			return rows;
		}
		for (int from = 0; from < numbers.size(); from += NUMBERS_PER_READ) {
			StringBuilder filter = new StringBuilder();
			for (String number : numbers.subList(from, Math.min(from + NUMBERS_PER_READ, numbers.size()))) {
				filter.append(filter.length() == 0 ? "" : " or ").append("No eq '").append(quoted(number)).append("'");
			}
			rows.addAll(readInvoices(filter.toString()));
		}
		return rows;
	}

	/** 2.2.1: invoice numbers per GET when they are read again by number (a short URL). */
	static final int NUMBERS_PER_READ = 20;

	/** The invoices of the filter; Prices_Including_VAT asked for until the page says it has none. */
	private List<NavPosInvoiceRow> readInvoices(String filter) {
		if (pricesIncludingVatOnPage) {
			try {
				return readInvoices(filter, true);
			} catch (NavPosPagesReadException e) {
				if (!unknownProperty(e, NavPosInvoiceRow.PRICES_INCLUDING_VAT)) {
					throw e;
				}
				pricesIncludingVatOnPage = false;
				log.info("navpospages: the page {} has no {}: read without it", properties.getPage().getInvoices(),
						NavPosInvoiceRow.PRICES_INCLUDING_VAT);
			}
		}
		return readInvoices(filter, false);
	}

	/**
	 * Release 2.2: the lowest invoice number of the page that starts with numberPrefix (one GET: $top=1, $orderby=No,
	 * $select=No), or null when the page has none. The years to read start at its year when the General Setup "Read ERP
	 * invoices after number" is empty and the head office has no invoice yet.
	 */
	@Override
	public String readFirstInvoiceNumber(String numberPrefix) {
		String page = properties.getPage().getInvoices();
		URI uri = page(page).queryParam("$filter", "startswith(No,'" + quoted(numberPrefix) + "')")
				.queryParam("$orderby", "No").queryParam("$top", 1).queryParam("$select", NavPosInvoiceRow.NO).build()
				.encode().toUri();
		NavPosCollection<NavPosInvoiceRow> body = get(page, uri,
				new ParameterizedTypeReference<NavPosCollection<NavPosInvoiceRow>>() {
				});
		if (body == null || body.getValue() == null || body.getValue().isEmpty()) {
			return null;
		}
		String number = body.getValue().get(0).text(NavPosInvoiceRow.NO);
		return number == null || number.trim().isEmpty() ? null : number.trim();
	}

	private List<NavPosInvoiceRow> readInvoices(String filter, boolean withPricesIncludingVat) {
		NavPosPagesProperties.Invoices settings = properties.getInvoices();
		String page = properties.getPage().getInvoices();
		int max = settings.getMaxPerRun();
		URI uri = page(page).queryParam("$filter", filter).queryParam("$orderby", "No").queryParam("$top", max)
				.queryParam("$select", invoiceFields(withPricesIncludingVat))
				.queryParam("$expand", settings.getLinesExpand().trim() + "($select=" + NavPosInvoiceLineRow.FIELDS + ")")
				.build().encode().toUri();
		List<NavPosInvoiceRow> rows = new ArrayList<>();
		URI next = uri;
		while (next != null && rows.size() < max) {
			NavPosCollection<NavPosInvoiceRow> body = get(page, next,
					new ParameterizedTypeReference<NavPosCollection<NavPosInvoiceRow>>() {
					});
			if (body == null || body.getValue() == null) {
				break;
			}
			rows.addAll(body.getValue());
			String link = body.getNextLink();
			next = link == null || link.trim().isEmpty() ? null : URI.create(link.trim());
		}
		List<NavPosInvoiceRow> kept = rows.size() > max ? new ArrayList<>(rows.subList(0, max)) : rows;
		for (NavPosInvoiceRow row : kept) {
			row.resolveLines(settings.getLinesExpand().trim());
		}
		return kept;
	}

	/** No, the customer field, Sell_to_Customer_Name, the two dates, Client_Franchise[, Prices_Including_VAT]. */
	private String invoiceFields(boolean withPricesIncludingVat) {
		Set<String> fields = new LinkedHashSet<>();
		fields.add(NavPosInvoiceRow.NO);
		fields.add(properties.getInvoices().getCustomerField().trim());
		fields.add(NavPosInvoiceRow.CUSTOMER_NAME);
		fields.add(NavPosInvoiceRow.DOCUMENT_DATE);
		fields.add(NavPosInvoiceRow.POSTING_DATE);
		fields.add(NavPosInvoiceRow.CLIENT_FRANCHISE);
		if (withPricesIncludingVat) {
			fields.add(NavPosInvoiceRow.PRICES_INCLUDING_VAT);
		}
		return String.join(",", fields);
	}

	/** True when the read failed with 400 because the page has no property of that name. */
	private static boolean unknownProperty(NavPosPagesReadException e, String property) {
		if (!(e.getCause() instanceof HttpStatusCodeException)) {
			return false;
		}
		HttpStatusCodeException http = (HttpStatusCodeException) e.getCause();
		return http.getRawStatusCode() == 400 && http.getResponseBodyAsString().contains("'" + property + "'");
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
