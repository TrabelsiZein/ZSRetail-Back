package com.digithink.zsretail.connector.zen.sync;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.connector.zen.client.ZenInvoiceSource;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.dto.ZenInvoiceRow;
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;
import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpInvoiceCustomers;
import com.digithink.zsretail.erp.api.ErpInvoiceStart;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.RunSummary;
import com.digithink.zsretail.erp.api.dto.ErpSupplyInvoiceDTO;

import lombok.extern.log4j.Log4j2;

/**
 * Item variants (ZEN), step 9: the supply invoices of ZEN's NAV, as Happyness's NavPosPagesInvoices (copied, not shared:
 * a connector sees only erp-api): one read per year prefix (FVE26) after the highest number the head office has of
 * that year, the starting point the General Setup "Read ERP invoices after number". ZenConnector answers
 * fetchSupplyInvoices and the seller name from here. Two differences: ZEN's page salesInvoices holds every posted sales
 * invoice of the company, so only those of the stores' customers are read ({@link ErpInvoiceCustomers}: none, nothing
 * read); a cancelled invoice is read past and left out (once the page gives cancelled: not yet). Nothing is kept between runs: the head office's numbers are the
 * memory. Its runs write the engine's {@link RunSummary} format.
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
@Log4j2
public class ZenInvoices {

	/** The run waits: no store's customer to read the invoices of. */
	public static final String NO_STORE_CUSTOMER = "no store has an ERP customer number";

	private final ZenInvoiceSource source;
	private final ErpInvoiceStart headOffice;
	private final ErpInvoiceCustomers customers;
	private final ZenProperties properties;
	private final ZenMapper mapper;
	/** Today, for the last year of the invoices to read. */
	private Supplier<LocalDate> today = LocalDate::now;

	/**
	 * The stores' customers from the head office, when it reads the ERP's invoices (headoffice.supply.source=ERP, the
	 * only installation that asks this class for them); none otherwise.
	 */
	@Autowired
	public ZenInvoices(ZenInvoiceSource source, ErpInvoiceStart headOffice, ObjectProvider<ErpInvoiceCustomers> customers,
			ZenProperties properties) {
		this(source, headOffice, () -> {
			ErpInvoiceCustomers found = customers.getIfAvailable();
			return found == null ? Collections.<String>emptySet() : found.storeCustomerNumbers();
		}, properties);
	}

	/** With given reads and customers: used by the tests. */
	public ZenInvoices(ZenInvoiceSource source, ErpInvoiceStart headOffice, ErpInvoiceCustomers customers,
			ZenProperties properties) {
		this.source = source;
		this.headOffice = headOffice;
		this.customers = customers;
		this.properties = properties;
		this.mapper = new ZenMapper(properties.getDefaultVat());
	}

	/** For the tests: a fixed day. */
	public void setToday(Supplier<LocalDate> today) {
		this.today = today;
	}

	/** The seller named on the stores' purchase invoices (invoices.seller-name). */
	public String sellerName() {
		return properties.getInvoices().getSellerName();
	}

	/**
	 * As Happyness: the years from a starting year up to the current one, then for each year the invoices of the stores'
	 * customers whose number starts with that year's prefix (FVE26), after the highest number the head office gives for
	 * it, else from the first invoice of the year. The starting year: the oldest year the head office has invoices of;
	 * else the General Setup number's (read after it); else the year of the stores' oldest invoice on the page (one GET);
	 * none: nothing to read. At most page-size invoices per year that are not cancelled ({@link #readYear}). Merged, each
	 * number once, by number. Dry run: read and summarised, nothing handed. No store with an ERP customer number: no GET,
	 * a warning, the summary says the run waits.
	 */
	public CatalogueRun<ErpSupplyInvoiceDTO> invoices(Map<String, String> highestByYear) {
		ZenProperties.Invoices settings = properties.getInvoices();
		RunSummary summary = new RunSummary("invoices", settings.getPage().trim(), properties.isDryRun());
		Set<String> stores = customers.storeCustomerNumbers();
		summary.put("storeCustomers", stores.size());
		if (stores.isEmpty()) {
			log.warn("zen: {}: no invoice read (the ERP customer number of a store is set on the head office's stores page)",
					NO_STORE_CUSTOMER);
			return run(new ArrayList<>(), summary.waiting(NO_STORE_CUSTOMER));
		}
		Map<String, String> highest = highestByYear == null ? new LinkedHashMap<>() : highestByYear;
		String readAfter = headOffice.invoicesReadAfter();
		Integer startYear = null;
		for (String prefix : highest.keySet()) {
			Integer year = settings.yearOf(prefix + "0");
			if (year != null && (startYear == null || year < startYear)) {
				startYear = year;
			}
		}
		if (startYear != null) {
			summary.put("startingPoint", "the invoices already at the head office (from " + startYear + ")");
			readAfter = null; // only the starting point: never read again before what is saved
		} else if (readAfter != null) {
			startYear = settings.yearOf(readAfter);
			summary.put("startingPoint", "after " + readAfter + " (General Setup)");
		} else {
			String first = source.readFirstInvoiceNumber(settings.getNumberPrefix().trim(), stores);
			startYear = settings.yearOf(first);
			summary.put("startingPoint",
					first == null ? "no invoice of the stores on the page" : "the stores' oldest invoice on the page: " + first);
		}
		int currentYear = today.get().getYear();
		Map<String, Object> years = new LinkedHashMap<>();
		List<ZenInvoiceRow> rows = new ArrayList<>();
		for (int year = startYear == null ? currentYear + 1 : Math.min(startYear, currentYear); year <= currentYear; year++) {
			String prefix = settings.yearPrefix(year);
			String after = readAfter(prefix, highest, readAfter);
			List<ZenInvoiceRow> read = readYear(prefix, after, stores, settings.getPageSize());
			Map<String, Object> ofYear = new LinkedHashMap<>();
			ofYear.put("after", after);
			ofYear.put("read", read.size());
			years.put(prefix, ofYear);
			rows.addAll(read);
		}
		summary.put("years", years);
		logCancelled(rows);
		ErpRead<ErpSupplyInvoiceDTO> mapped = mapper.invoices(rows);
		summary.read(mapped);
		Map<String, ErpSupplyInvoiceDTO> byNumber = new TreeMap<>();
		for (ErpSupplyInvoiceDTO invoice : mapped.getRows()) {
			for (String prefix : years.keySet()) {
				if (invoice.getNumber().startsWith(prefix)) {
					invoice.setYearPrefix(prefix);
				}
			}
			byNumber.putIfAbsent(invoice.getNumber(), invoice);
		}
		List<ErpSupplyInvoiceDTO> invoices = new ArrayList<>(byNumber.values());
		summary.put("handed", properties.isDryRun() ? 0 : invoices.size());
		return run(invoices, summary);
	}

	/**
	 * As Happyness (2.2.1): given invoices again, by number, whatever their customer, mapped as the new ones (a cancelled
	 * one left out); the year prefix of each from its number. A number the page no longer has is not in the answer.
	 */
	public CatalogueRun<ErpSupplyInvoiceDTO> invoicesByNumbers(List<String> numbers) {
		ZenProperties.Invoices settings = properties.getInvoices();
		RunSummary summary = new RunSummary("invoices", settings.getPage().trim(), properties.isDryRun());
		List<String> asked = numbers == null ? new ArrayList<>() : numbers;
		summary.put("readAgain", asked.size());
		List<ZenInvoiceRow> rows = asked.isEmpty() ? new ArrayList<>() : source.readInvoicesByNumbers(asked);
		logCancelled(rows);
		ErpRead<ErpSupplyInvoiceDTO> mapped = mapper.invoices(rows);
		summary.read(mapped);
		List<ErpSupplyInvoiceDTO> invoices = new ArrayList<>();
		for (ErpSupplyInvoiceDTO invoice : mapped.getRows()) {
			Integer year = settings.yearOf(invoice.getNumber());
			invoice.setYearPrefix(year == null ? null : settings.yearPrefix(year));
			invoices.add(invoice);
		}
		summary.put("handed", properties.isDryRun() ? 0 : invoices.size());
		return run(invoices, summary);
	}

	/**
	 * The invoices of a year after `after`, page after page, until pageSize of them are not cancelled or the year has no
	 * more; each page after the last number of the one before. A cancelled invoice is never saved, so the next run asks
	 * again after the highest number saved and reads it again: were they not read past, pageSize cancelled invoices in a
	 * row would hold the year for ever. Stops too when a page does not go past the one before (an ERP that ignores the
	 * filter).
	 */
	private List<ZenInvoiceRow> readYear(String prefix, String after, Set<String> stores, int pageSize) {
		List<ZenInvoiceRow> rows = new ArrayList<>();
		int kept = 0;
		String cursor = after;
		while (true) {
			List<ZenInvoiceRow> page = source.readInvoicesAfter(prefix, cursor, stores);
			for (ZenInvoiceRow row : page) {
				if (kept >= pageSize) {
					break;
				}
				rows.add(row);
				kept += row.isCancelled() ? 0 : 1;
			}
			if (kept >= pageSize || page.size() < pageSize) {
				return rows;
			}
			String last = page.get(page.size() - 1).getNumber();
			if (last.isEmpty() || (cursor != null && last.compareTo(cursor.trim()) <= 0)) {
				return rows;
			}
			cursor = last;
		}
	}

	/** One line naming the cancelled invoices read (left out, never handed). */
	private static void logCancelled(List<ZenInvoiceRow> rows) {
		List<String> cancelled = rows.stream().filter(ZenInvoiceRow::isCancelled).map(ZenInvoiceRow::getNumber)
				.collect(Collectors.toList());
		if (!cancelled.isEmpty()) {
			log.info("zen: {} cancelled invoice(s) left out: {}", cancelled.size(), cancelled);
		}
	}

	/** The highest number given for the prefix; else the starting number when it is of that year; else null. */
	private static String readAfter(String prefix, Map<String, String> highestByYear, String startNumber) {
		String highest = highestByYear.get(prefix);
		if (highest != null && !highest.trim().isEmpty()) {
			return highest.trim();
		}
		return startNumber != null && startNumber.startsWith(prefix) ? startNumber : null;
	}

	private <T> CatalogueRun<T> run(List<T> handed, RunSummary summary) {
		return new CatalogueRun<>(properties.isDryRun() ? new ArrayList<>() : handed, summary.values(),
				source.invoicesPageUrl());
	}
}
