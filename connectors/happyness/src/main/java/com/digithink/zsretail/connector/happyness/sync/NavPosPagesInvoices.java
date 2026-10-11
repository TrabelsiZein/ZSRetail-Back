package com.digithink.zsretail.connector.happyness.sync;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.happyness.HappynessConnectorDescriptor;
import com.digithink.zsretail.connector.happyness.client.NavPosPagesSource;
import com.digithink.zsretail.connector.happyness.config.NavPosPagesProperties;
import com.digithink.zsretail.connector.happyness.dto.NavPosInvoiceRow;
import com.digithink.zsretail.connector.happyness.mapper.NavPosPagesMapper;
import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpInvoiceStart;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.RunSummary;
import com.digithink.zsretail.erp.api.dto.ErpSupplyInvoiceDTO;

/**
 * Code cleanup step 3: invoices from the ERP, a thin part of the navpospages connector (the invoices page and its
 * numbers by year prefix, FVV26...), out of the catalogue engine (which reads only through ErpCatalogueSource);
 * NavPosPagesConnector answers fetchSupplyInvoices and the seller name from here. Nothing is kept between runs: the head
 * office's numbers are the memory. Its runs write the engine's {@link RunSummary} format.
 */
@Component
@ConditionalOnErpConnector(HappynessConnectorDescriptor.NAME)
public class NavPosPagesInvoices {

	private final NavPosPagesSource source;
	private final ErpInvoiceStart headOffice;
	private final NavPosPagesProperties properties;
	private final NavPosPagesMapper mapper;
	/** Release 2.2: today, for the last year of the invoices to read. */
	private Supplier<LocalDate> today = LocalDate::now;

	@Autowired
	public NavPosPagesInvoices(NavPosPagesSource source, ErpInvoiceStart headOffice,
			NavPosPagesProperties properties) {
		this(source, headOffice, properties, new NavPosPagesMapper(properties.getDefaultVat(), properties.getPriceIncludesVat()));
	}

	/** With a given mapper: used by the tests. */
	public NavPosPagesInvoices(NavPosPagesSource source, ErpInvoiceStart headOffice, NavPosPagesProperties properties,
			NavPosPagesMapper mapper) {
		this.source = source;
		this.headOffice = headOffice;
		this.properties = properties;
		this.mapper = mapper;
	}

	/** For the tests: a fixed day. */
	public void setToday(Supplier<LocalDate> today) {
		this.today = today;
	}

	/** Invoices from the ERP, step (c): the seller named on the stores' purchase invoices (invoices.seller-name). */
	public String sellerName() {
		return properties.getInvoices().getSellerName();
	}

	/**
	 * Release 2.2: the years are worked out here, from a starting year up to the current one, then for each year the
	 * invoices whose number starts with that year's prefix (FVV26) after the highest number the head office gives for it
	 * (highestByYear, by prefix), else from the first invoice of the year. The starting year:
	 * <ul>
	 * <li>the head office already has invoices: the year of the oldest of them (the General Setup "Read ERP invoices after
	 * number" is then only the starting point it was: changing it reads no old invoice again);</li>
	 * <li>else that setting: its year, read after it;</li>
	 * <li>else (empty) the year of the oldest invoice on the page (one GET); none: nothing to read.</li>
	 * </ul>
	 * At most max-per-run per year. Merged, each number once, by number. Nothing is kept here: the head office's numbers are
	 * the memory. Dry run: read and summarised, nothing handed.
	 */
	public CatalogueRun<ErpSupplyInvoiceDTO> invoices(Map<String, String> highestByYear) {
		String page = properties.getPage().getInvoices();
		RunSummary summary = new RunSummary("invoices", page, properties.isDryRun());
		NavPosPagesProperties.Invoices settings = properties.getInvoices();
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
			String first = source.readFirstInvoiceNumber(settings.getNumberPrefix().trim());
			startYear = settings.yearOf(first);
			summary.put("startingPoint", first == null ? "no invoice on the page" : "the oldest invoice on the page: " + first);
		}
		int currentYear = today.get().getYear();
		Map<String, Object> years = new LinkedHashMap<>();
		List<NavPosInvoiceRow> rows = new ArrayList<>();
		for (int year = startYear == null ? currentYear + 1 : Math.min(startYear, currentYear); year <= currentYear; year++) {
			String prefix = settings.yearPrefix(year);
			String after = readAfter(prefix, highest, readAfter);
			List<NavPosInvoiceRow> read = source.readInvoicesAfter(prefix, after);
			Map<String, Object> ofYear = new LinkedHashMap<>();
			ofYear.put("after", after);
			ofYear.put("read", read.size());
			years.put(prefix, ofYear);
			rows.addAll(read);
		}
		summary.put("years", years);
		ErpRead<ErpSupplyInvoiceDTO> mapped = mapper.invoices(rows, settings.getCustomerField().trim());
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
		return run(invoices, summary, page);
	}

	/**
	 * 2.2.1: given invoices again, by number (those a 2.2.0 head office held for a decimal quantity), mapped as the new
	 * ones; the year prefix of each from its number. A number the page no longer has is not in the answer.
	 */
	public CatalogueRun<ErpSupplyInvoiceDTO> invoicesByNumbers(List<String> numbers) {
		String page = properties.getPage().getInvoices();
		RunSummary summary = new RunSummary("invoices", page, properties.isDryRun());
		NavPosPagesProperties.Invoices settings = properties.getInvoices();
		List<String> asked = numbers == null ? new ArrayList<>() : numbers;
		summary.put("readAgain", asked.size());
		List<NavPosInvoiceRow> rows = asked.isEmpty() ? new ArrayList<>() : source.readInvoicesByNumbers(asked);
		ErpRead<ErpSupplyInvoiceDTO> mapped = mapper.invoices(rows, settings.getCustomerField().trim());
		summary.read(mapped);
		List<ErpSupplyInvoiceDTO> invoices = new ArrayList<>();
		for (ErpSupplyInvoiceDTO invoice : mapped.getRows()) {
			Integer year = settings.yearOf(invoice.getNumber());
			invoice.setYearPrefix(year == null ? null : settings.yearPrefix(year));
			invoices.add(invoice);
		}
		summary.put("handed", properties.isDryRun() ? 0 : invoices.size());
		return run(invoices, summary, page);
	}

	/** The highest number given for the prefix; else the starting number when it is of that year; else null. */
	private static String readAfter(String prefix, Map<String, String> highestByYear, String startNumber) {
		String highest = highestByYear.get(prefix);
		if (highest != null && !highest.trim().isEmpty()) {
			return highest.trim();
		}
		return startNumber != null && startNumber.startsWith(prefix) ? startNumber : null;
	}

	private <T> CatalogueRun<T> run(List<T> handed, RunSummary summary, String page) {
		return new CatalogueRun<>(properties.isDryRun() ? new ArrayList<>() : handed, summary.values(),
				source.pageUrl(page));
	}
}
