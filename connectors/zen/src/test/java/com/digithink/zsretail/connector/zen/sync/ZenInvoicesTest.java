package com.digithink.zsretail.connector.zen.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.connector.zen.client.ZenInvoiceSource;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.connector.ZenConnector;
import com.digithink.zsretail.connector.zen.dto.ZenInvoiceRow;
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;
import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.erp.api.dto.PullOperationResult;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Item variants (ZEN), step 9: the invoice runs on a fake salesInvoices page holding the invoices of two stores and of
 * another customer of the company: only the stores' are read, per year from the starting point (as Happyness), the
 * cancelled ones read past and left out, none without a store's customer; ZenConnector hands the runs and keeps their
 * summary for the communications log.
 */
class ZenInvoicesTest {

	static final String PAGE = "http://nav.test/api/sat/franchise/v1.0/companies(1d6c4f00-6ac9-f111-9db8-ad3d0c589c9c)/salesInvoices";
	static final String OTHER_CUSTOMER = "4119999";

	/** The page: by number, filtered as NAV filters (prefix, after, the customers), page-size per GET. */
	class FakePage implements ZenInvoiceSource {
		final List<ZenInvoiceRow> invoices = new ArrayList<>();
		final List<String> reads = new ArrayList<>();

		void add(String number, String customer, boolean cancelled) {
			ZenInvoiceRow row = new ZenInvoiceRow();
			row.set(ZenInvoiceRow.NO, TextNode.valueOf(number));
			row.set(ZenInvoiceRow.CUSTOMER_NO, TextNode.valueOf(customer));
			row.set(ZenInvoiceRow.CANCELLED, BooleanNode.valueOf(cancelled));
			invoices.add(row.resolveLines("salesInvoiceLines"));
		}

		private List<ZenInvoiceRow> sorted() {
			List<ZenInvoiceRow> rows = new ArrayList<>(invoices);
			rows.sort((a, b) -> a.getNumber().compareTo(b.getNumber()));
			return rows;
		}

		private boolean ofCustomers(ZenInvoiceRow row, Collection<String> customers) {
			return customers.stream().anyMatch(c -> c.toUpperCase(Locale.ROOT).equals(row.text(ZenInvoiceRow.CUSTOMER_NO)));
		}

		@Override
		public List<ZenInvoiceRow> readInvoicesAfter(String yearPrefix, String afterNumber, Collection<String> customers) {
			reads.add(yearPrefix + " after " + afterNumber);
			return sorted().stream().filter(row -> row.getNumber().startsWith(yearPrefix))
					.filter(row -> afterNumber == null || row.getNumber().compareTo(afterNumber) > 0)
					.filter(row -> ofCustomers(row, customers)).limit(properties.getInvoices().getPageSize())
					.collect(Collectors.toList());
		}

		@Override
		public List<ZenInvoiceRow> readInvoicesByNumbers(List<String> numbers) {
			reads.add("numbers " + numbers);
			return sorted().stream().filter(row -> numbers.contains(row.getNumber())).collect(Collectors.toList());
		}

		@Override
		public String readFirstInvoiceNumber(String numberPrefix, Collection<String> customers) {
			reads.add("first " + numberPrefix);
			return sorted().stream().filter(row -> row.getNumber().startsWith(numberPrefix))
					.filter(row -> ofCustomers(row, customers)).map(ZenInvoiceRow::getNumber).findFirst().orElse(null);
		}

		@Override
		public String invoicesPageUrl() {
			return PAGE;
		}
	}

	private ZenProperties properties;
	private FakePage page;
	private String readAfter;
	private Set<String> stores;
	private ZenInvoices invoices;

	@BeforeEach
	void setUp() {
		properties = new ZenProperties();
		properties.setDefaultVat(19);
		page = new FakePage();
		stores = new LinkedHashSet<>(Arrays.asList("4110001", "4110002"));
		invoices = new ZenInvoices(page, () -> readAfter, () -> stores, properties);
		invoices.setToday(() -> LocalDate.of(2026, 10, 23));
	}

	/** The ERP's invoices of 2025 and 2026, the stores' and another customer's. */
	private void invoicesOfTheErp() {
		page.add("FVE2400009", OTHER_CUSTOMER, false);
		page.add("FVE2500001", "4110001", false);
		page.add("FVE2500002", OTHER_CUSTOMER, false);
		page.add("FVE2500003", "4110002", false);
		page.add("FVE2600001", "4110001", false);
		page.add("FVE2600002", OTHER_CUSTOMER, false);
		page.add("FVE2600003", "4110002", false);
	}

	private static Map<String, String> highest(String... prefixAndNumber) {
		Map<String, String> highest = new HashMap<>();
		for (int i = 0; i < prefixAndNumber.length; i += 2) {
			highest.put(prefixAndNumber[i], prefixAndNumber[i + 1]);
		}
		return highest;
	}

	private static List<String> numbers(List<ErpSupplyInvoiceDTO> handed) {
		return handed.stream().map(ErpSupplyInvoiceDTO::getNumber).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Nothing saved, setting empty: from the year of the stores' oldest invoice; another customer's never read; the year prefix set")
	void fromTheStoresOldestInvoice() {
		invoicesOfTheErp();
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoices.invoices(new HashMap<>());
		assertEquals(Arrays.asList("first FVE", "FVE25 after null", "FVE26 after null"), page.reads,
				"2024 has only another customer's invoice: not a year to read");
		assertEquals(Arrays.asList("FVE2500001", "FVE2500003", "FVE2600001", "FVE2600003"), numbers(run.getHanded()));
		assertEquals(Arrays.asList("FVE25", "FVE25", "FVE26", "FVE26"),
				run.getHanded().stream().map(ErpSupplyInvoiceDTO::getYearPrefix).collect(Collectors.toList()));
		assertEquals("the stores' oldest invoice on the page: FVE2500001", run.getSummary().get("startingPoint"));
		assertEquals(2, run.getSummary().get("storeCustomers"));
		assertEquals(PAGE, run.getUrl());
	}

	@Test
	@DisplayName("Invoices at the head office: each year after its highest number, from the oldest year saved; the General Setup no longer read")
	void afterTheHighestSaved() {
		invoicesOfTheErp();
		readAfter = "FVE2600003";
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoices.invoices(highest("FVE25", "FVE2500003", "FVE26", "FVE2600001"));
		assertEquals(Arrays.asList("FVE25 after FVE2500003", "FVE26 after FVE2600001"), page.reads);
		assertEquals(Arrays.asList("FVE2600003"), numbers(run.getHanded()));
		assertEquals("the invoices already at the head office (from 2025)", run.getSummary().get("startingPoint"));
	}

	@Test
	@DisplayName("The General Setup number: its year after it, the next years from their first invoice")
	void afterTheGeneralSetupNumber() {
		invoicesOfTheErp();
		readAfter = "FVE2500001";
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoices.invoices(null);
		assertEquals(Arrays.asList("FVE25 after FVE2500001", "FVE26 after null"), page.reads);
		assertEquals(Arrays.asList("FVE2500003", "FVE2600001", "FVE2600003"), numbers(run.getHanded()));
		assertEquals("after FVE2500001 (General Setup)", run.getSummary().get("startingPoint"));
	}

	@Test
	@DisplayName("No store with an ERP customer number: no GET, nothing handed, the run waits with the reason")
	void noStoreCustomer() {
		invoicesOfTheErp();
		stores = new LinkedHashSet<>();
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoices.invoices(new HashMap<>());
		assertTrue(page.reads.isEmpty(), page.reads.toString());
		assertTrue(run.getHanded().isEmpty());
		assertEquals(ZenInvoices.NO_STORE_CUSTOMER, run.getSummary().get("waiting"));
		assertEquals(0, run.getSummary().get("handed"));
		assertEquals(0, run.getSummary().get("storeCustomers"));
	}

	@Test
	@DisplayName("Cancelled invoices: read past (a page of them does not hold the year), left out and counted; page-size counts the others only")
	void cancelledReadPast() {
		properties.getInvoices().setPageSize(2);
		page.add("FVE2600001", "4110001", true);
		page.add("FVE2600002", "4110002", true);
		page.add("FVE2600003", "4110001", false);
		page.add("FVE2600004", "4110001", true);
		page.add("FVE2600005", "4110002", false);
		page.add("FVE2600006", "4110002", false);
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoices.invoices(highest("FVE26", "FVE2600000"));
		assertEquals(Arrays.asList("FVE26 after FVE2600000", "FVE26 after FVE2600002", "FVE26 after FVE2600004"),
				page.reads, "each page after the last number of the one before");
		assertEquals(Arrays.asList("FVE2600003", "FVE2600005"), numbers(run.getHanded()),
				"two not cancelled: FVE2600006 waits for the next run");
		@SuppressWarnings("unchecked")
		Map<String, Integer> leftOut = (Map<String, Integer>) run.getSummary().get("leftOut");
		assertEquals(Integer.valueOf(3), leftOut.get(ZenMapper.CANCELLED));
		assertEquals(5, run.getSummary().get("read"));
	}

	@Test
	@DisplayName("The highest invoice cancelled: nothing handed, the next run asks after the highest saved again (never held)")
	void highestCancelled() {
		page.add("FVE2600001", "4110001", false);
		page.add("FVE2600002", "4110001", true);
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoices.invoices(highest("FVE26", "FVE2600001"));
		assertTrue(run.getHanded().isEmpty());
		page.add("FVE2600003", "4110002", false);
		CatalogueRun<ErpSupplyInvoiceDTO> next = invoices.invoices(highest("FVE26", "FVE2600001"));
		assertEquals(Arrays.asList("FVE26 after FVE2600001", "FVE26 after FVE2600001"), page.reads);
		assertEquals(Arrays.asList("FVE2600003"), numbers(next.getHanded()));
	}

	@Test
	@DisplayName("By number: whatever the customer, the year prefix from the number, a cancelled one left out; none asked: no GET")
	void byNumbers() {
		invoicesOfTheErp();
		page.add("FVE2600009", "4110001", true);
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoices.invoicesByNumbers(Arrays.asList("FVE2500002", "FVE2600009",
				"FVE2699999"));
		assertEquals(Arrays.asList("numbers [FVE2500002, FVE2600009, FVE2699999]"), page.reads);
		assertEquals(Arrays.asList("FVE2500002"), numbers(run.getHanded()));
		assertEquals("FVE25", run.getHanded().get(0).getYearPrefix());
		assertEquals(3, run.getSummary().get("readAgain"));
		page.reads.clear();
		assertTrue(invoices.invoicesByNumbers(new ArrayList<>()).getHanded().isEmpty());
		assertTrue(page.reads.isEmpty(), "no number: no GET");
	}

	@Test
	@DisplayName("Dry run: read and summarised, nothing handed")
	void dryRun() {
		invoicesOfTheErp();
		properties.setDryRun(true);
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoices.invoices(new HashMap<>());
		assertTrue(run.getHanded().isEmpty());
		assertEquals(0, run.getSummary().get("handed"));
		assertEquals(true, run.getSummary().get("dryRun"));
		assertEquals(4, run.getSummary().get("read"));
	}

	@Test
	@DisplayName("ZenConnector: the runs handed, their summary for the communications log; the seller name ZEN by default; a catalogue fetch clears the summary")
	void connector() {
		invoicesOfTheErp();
		ZenConnector connector = new ZenConnector(invoices);
		assertEquals("ZEN", connector.supplyInvoiceSellerName());
		List<ErpSupplyInvoiceDTO> handed = connector.fetchSupplyInvoices(highest("FVE26", "FVE2600001"));
		assertEquals(Arrays.asList("FVE2600003"), numbers(handed));
		PullOperationResult<?> pull = connector.getLastPullOperationResult();
		assertEquals(PAGE, pull.getUrl());
		assertEquals("invoices", ((Map<?, ?>) pull.getRawResponse()).get("run"));
		assertEquals(1, ((Map<?, ?>) pull.getRawResponse()).get("handed"));
		assertEquals(Arrays.asList("FVE2500002"),
				numbers(connector.fetchSupplyInvoicesByNumbers(Collections.singletonList("FVE2500002"))));
		connector.fetchItems(null);
		assertNull(connector.getLastPullOperationResult(), "no run: nothing for the communications log");
		properties.getInvoices().setSellerName("ZEN Franchise");
		assertEquals("ZEN Franchise", connector.supplyInvoiceSellerName());
	}
}
