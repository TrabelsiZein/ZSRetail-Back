package com.digithink.zsretail.erp.navpospages.sync;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesSource;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;
import com.digithink.zsretail.erp.navpospages.mapper.NavPosPagesMapper;
import com.digithink.zsretail.erp.navpospages.mapper.NavPosResult;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;

/**
 * ERP catalogue, step 6: changes only. Each run reads a whole page of the ERP (GET only), compares it with the head
 * office tables and takes only the new and changed rows, in a stable order (by code; barcodes by Entry_No). The items
 * run applies them all itself, in packets of packet-size, each packet in its own transaction ({@link NavPosPagesImport}),
 * one items run at a time; families, sub-families and barcodes hand one packet to the import (ErpItemBootstrapService,
 * unchanged) per run. The head office tables are the memory of what was seen: a row the import did not save is still
 * different at the next run and is taken again.
 * <ul>
 * <li><b>Order.</b> Sub-families wait for the families, items for both: the waiting run reads the categories page (small)
 * and waits while a family or sub-family of the ERP is not at the head office yet. Barcodes wait for the items: each item
 * run writes, before handing over, how many items of the ERP are not at the head office yet; the barcode run waits while
 * that number is above 0 or no item run happened. The items run counts before applying, then again once every packet
 * is applied: 0 proves the new items were saved.</li>
 * <li><b>Items.</b> Compared on what the import applies: name, description, VAT, active, ERP id, family and sub-family
 * (only when the ERP's one exists at the head office), the price with VAT at 3 decimals, discount group and maximum
 * discount (the import clears them). A null or zero price makes the item inactive (the mapper). Items of the ERP
 * (erp_external_id set) no longer in the location are handed inactive, never deleted, whatever their number; only an
 * empty ERP answer (a broken connection or a wrong location code, never a cleanup) deactivates nothing. Hand-made
 * items, packs made at the head office and TAX_STAMP have no ERP id and are never touched. A blank Description (the
 * name and the description) never replaces those of an item already at the head office; a new item takes its code as
 * name.</li>
 * <li><b>Barcodes.</b> A cursor on Entry_No, in the state table. It only moves over rows that are at the head office
 * already (checked against its barcode table), left out (blank, item not at the head office) or replaced by a later row
 * of the same barcode: it never passes a row that was handed but not saved. New items arriving after the barcodes
 * started are kept in the state table ("needs its barcodes"); their barcodes are read by Item_No and handed before the
 * cursor goes on; an item leaves that list once all its barcodes are at the head office.</li>
 * <li><b>Invoices</b> (invoices from the ERP, step a): read by number per configured year, see {@link #invoices}; no
 * state kept here.</li>
 * <li><b>Dry run.</b> Reads, compares and summarises; hands nothing; does not write the state table.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class NavPosPagesSync {

	static final String ITEMS_RUN = "items.run";
	static final String ITEMS_PENDING = "items.pending";
	static final String BARCODE_CURSOR = "barcodes.cursor";
	static final String NEEDS_BARCODES = "needs-barcodes:";

	/** Item numbers per $filter of the barcodes of new items. */
	static final int ITEMS_PER_BARCODE_CALL = 20;

	public static final String TAX_STAMP_ITEM = "tax stamp item (TAX_STAMP_ERP_ITEM_CODE)";
	public static final String ITEM_NOT_AT_HEAD_OFFICE = "item not at the head office";
	public static final String REPLACED_BY_LATER_ROW = "same barcode, lower Entry_No";

	static final String ITEMS_ALREADY_RUNNING = "an items run is already running: this one does not start";

	private final NavPosPagesSource source;
	private final NavPosPagesHeadOffice headOffice;
	private final NavPosPagesState state;
	private final NavPosPagesProperties properties;
	private final NavPosPagesImport importer;
	private final NavPosPagesMapper mapper;
	/** One items run at a time (the scheduler and "Run now" may meet). */
	private final AtomicBoolean itemsRunning = new AtomicBoolean();

	public NavPosPagesSync(NavPosPagesSource source, NavPosPagesHeadOffice headOffice, NavPosPagesState state,
			NavPosPagesProperties properties, NavPosPagesImport importer) {
		this.source = source;
		this.headOffice = headOffice;
		this.state = state;
		this.properties = properties;
		this.importer = importer;
		this.mapper = new NavPosPagesMapper(properties.getDefaultVat(), properties.getPriceIncludesVat());
	}

	// ─── Families ───────────────────────────────────────────────

	public NavPosRun<ErpItemFamilyDTO> families() {
		String page = properties.getPage().getCategories();
		Summary summary = new Summary("families", page, properties.isDryRun());
		NavPosResult<ErpItemFamilyDTO> read = mapper.families(source.readCategories());
		summary.read(read);
		Map<String, NavPosPagesHeadOffice.Family> local = headOffice.families();
		List<ErpItemFamilyDTO> changes = new ArrayList<>();
		int fresh = 0;
		for (ErpItemFamilyDTO family : read.getRows()) {
			NavPosPagesHeadOffice.Family here = local.get(family.getCode());
			if (here == null) {
				fresh++;
				changes.add(family);
			} else if (differs(family.getCode(), family.getName(), family.getDescription(), here)) {
				changes.add(family);
			}
		}
		changes.sort(Comparator.comparing(ErpItemFamilyDTO::getCode));
		List<ErpItemFamilyDTO> handed = summary.handOver(changes, fresh, cap());
		return run(handed, summary, page);
	}

	// ─── Sub-families ───────────────────────────────────────────

	public NavPosRun<ErpItemSubFamilyDTO> subFamilies() {
		String page = properties.getPage().getCategories();
		Summary summary = new Summary("sub-families", page, properties.isDryRun());
		List<NavPosCategoryRow> categories = source.readCategories();
		String waiting = missingFamilies(categories, false);
		if (waiting != null) {
			return run(new ArrayList<>(), summary.waiting(waiting), page);
		}
		NavPosResult<ErpItemSubFamilyDTO> read = mapper.subFamilies(categories);
		summary.read(read);
		Map<String, NavPosPagesHeadOffice.SubFamily> local = headOffice.subFamilies();
		List<ErpItemSubFamilyDTO> changes = new ArrayList<>();
		int fresh = 0;
		for (ErpItemSubFamilyDTO subFamily : read.getRows()) {
			NavPosPagesHeadOffice.SubFamily here = local.get(subFamily.getCode());
			if (here == null) {
				fresh++;
				changes.add(subFamily);
			} else if (differs(subFamily.getCode(), subFamily.getName(), subFamily.getDescription(), here)
					|| !Objects.equals(subFamily.getFamilyExternalId(), here.familyCode)) {
				changes.add(subFamily);
			}
		}
		changes.sort(Comparator.comparing(ErpItemSubFamilyDTO::getCode));
		List<ErpItemSubFamilyDTO> handed = summary.handOver(changes, fresh, cap());
		return run(handed, summary, page);
	}

	/**
	 * Null when every family (and, with subFamiliesToo, every sub-family) of this read of the categories is at the head
	 * office; otherwise the reason to wait. Read from the ERP page itself: no run of the families job has to be counted.
	 */
	private String missingFamilies(List<NavPosCategoryRow> categories, boolean subFamiliesToo) {
		Set<String> families = headOffice.families().keySet();
		long missing = mapper.families(categories).getRows().stream().filter(f -> !families.contains(f.getCode())).count();
		if (missing > 0) {
			return "waiting for families: " + missing + " families of the ERP not at the head office yet";
		}
		if (subFamiliesToo) {
			Set<String> subFamilies = headOffice.subFamilies().keySet();
			missing = mapper.subFamilies(categories).getRows().stream().filter(f -> !subFamilies.contains(f.getCode()))
					.count();
			if (missing > 0) {
				return "waiting for sub-families: " + missing + " sub-families of the ERP not at the head office yet";
			}
		}
		return null;
	}

	/** Name (the import falls back to the code), description (null = blank), active, ERP id. */
	private static boolean differs(String code, String name, String description, NavPosPagesHeadOffice.Family here) {
		return !text(name, code).equals(text(here.name, null)) || !text(description, null).equals(text(here.description, null))
				|| Boolean.FALSE.equals(here.active) || !code.equals(here.erpExternalId);
	}

	// ─── Items ──────────────────────────────────────────────────

	/**
	 * Reads, compares, then applies every change of the run (new, changed, deactivated) in packets of packet-size by
	 * code, each packet in its own transaction. A failed packet ends the run in error; the packets before it stay and the
	 * next run goes on with what is left (the head office tables are the memory). The rows applied are in the run, for
	 * the tests; the connector hands nothing more to the job.
	 */
	public NavPosRun<ErpItemDTO> items() {
		if (!itemsRunning.compareAndSet(false, true)) {
			throw new ErpSyncWarningException(ITEMS_ALREADY_RUNNING);
		}
		try {
			return readAndApplyItems();
		} finally {
			itemsRunning.set(false);
		}
	}

	private NavPosRun<ErpItemDTO> readAndApplyItems() {
		String page = properties.getPage().getItems();
		Summary summary = new Summary("items", page, properties.isDryRun());
		String waiting = missingFamilies(source.readCategories(), true);
		if (waiting != null) {
			return run(new ArrayList<>(), summary.waiting(waiting), page);
		}
		List<NavPosStockRow> rows = source.readItems();
		NavPosResult<ErpItemDTO> read = mapper.items(rows);
		summary.read(read);
		Map<String, NavPosPagesHeadOffice.Item> local = headOffice.items();
		Set<String> familyCodes = headOffice.families().keySet();
		Set<String> subFamilyCodes = headOffice.subFamilies().keySet();
		String taxStamp = headOffice.taxStampErpCode();

		List<ErpItemDTO> changes = new ArrayList<>();
		Set<String> freshCodes = new HashSet<>();
		Set<String> inErp = new HashSet<>();
		int changed = 0;
		for (ErpItemDTO item : read.getRows()) {
			inErp.add(item.getCode());
			if (item.getCode().equals(taxStamp)) {
				summary.leftOut(TAX_STAMP_ITEM); // the import never saves it: it would wait for ever
				continue;
			}
			NavPosPagesHeadOffice.Item here = local.get(item.getCode());
			if (here == null) {
				freshCodes.add(item.getCode()); // a blank name: the import gives it the code
				changes.add(item);
				continue;
			}
			// A blank Description (name and description) never replaces the head office's: kept, not a change
			if (text(item.getName(), null).isEmpty()) {
				item.setName(here.name);
			}
			if (text(item.getDescription(), null).isEmpty()) {
				item.setDescription(here.description);
			}
			if (differs(item, here, familyCodes, subFamilyCodes)) {
				changed++;
				changes.add(item);
			}
		}

		// Items of the ERP no longer in the location: inactive, never deleted, whatever their number; not on an empty
		// answer (a broken connection or a wrong location code, never a cleanup)
		List<ErpItemDTO> gone = new ArrayList<>();
		for (NavPosPagesHeadOffice.Item here : local.values()) {
			if (here.fromErp() && here.isActive() && !here.code.equals(taxStamp) && !inErp.contains(here.code)) {
				gone.add(inactiveCopy(here));
			}
		}
		int deactivated = 0;
		if (!gone.isEmpty()) {
			if (rows.isEmpty()) {
				summary.guard("the ERP answered no item for the location: " + gone.size()
						+ " items not deactivated");
			} else {
				changes.addAll(gone);
				deactivated = gone.size();
			}
		}
		changes.sort(Comparator.comparing(ErpItemDTO::getCode));
		summary.put("new", freshCodes.size());
		summary.put("changed", changed);
		summary.put("deactivated", deactivated);
		if (properties.isDryRun()) {
			summary.put("applied", 0);
			summary.put("packets", 0);
			return run(new ArrayList<>(), summary, page);
		}

		// Counted before applying: while a packet of new items is not saved, the barcodes wait
		saveRun(ITEMS_RUN, ITEMS_PENDING, freshCodes.size());
		boolean barcodesStarted = cursor() > 0;
		int size = properties.getPacketSize();
		int packets = (changes.size() + size - 1) / size;
		List<ErpItemDTO> applied = new ArrayList<>();
		for (int from = 0; from < changes.size(); from += size) {
			List<ErpItemDTO> packet = new ArrayList<>(changes.subList(from, Math.min(from + size, changes.size())));
			// New items once the barcodes have started: their barcodes may be behind the cursor
			if (barcodesStarted) {
				for (ErpItemDTO item : packet) {
					if (freshCodes.contains(item.getCode())) {
						state.put(NEEDS_BARCODES + item.getCode(), "1");
					}
				}
			}
			try {
				importer.items(packet);
			} catch (RuntimeException e) {
				throw new IllegalStateException("Items: packet " + (from / size + 1) + " of " + packets + " failed, "
						+ applied.size() + " of " + changes.size() + " rows applied before it (the next run goes on): "
						+ e.getMessage(), e);
			}
			applied.addAll(packet);
		}
		summary.put("applied", applied.size());
		summary.put("packets", packets);
		if (!freshCodes.isEmpty()) {
			// Counted again once applied: the new items the import did not save (if any) keep the barcodes waiting
			Set<String> saved = headOffice.items().keySet();
			state.put(ITEMS_PENDING, String.valueOf(freshCodes.stream().filter(code -> !saved.contains(code)).count()));
		}
		return run(applied, summary, page);
	}

	/** The fields the import applies, the price with VAT at 3 decimals. */
	private static boolean differs(ErpItemDTO item, NavPosPagesHeadOffice.Item here, Set<String> familyCodes,
			Set<String> subFamilyCodes) {
		if (!text(item.getName(), item.getCode()).equals(text(here.name, null))
				|| !text(item.getDescription(), null).equals(text(here.description, null))
				|| !Objects.equals(item.getDefaultVAT(), here.defaultVat) || item.getActive() != here.isActive()
				|| !item.getCode().equals(here.erpExternalId) || !text(here.itemDiscGroup, null).isEmpty()
				|| here.maximumAuthorizedDiscount != null) {
			return true;
		}
		if (withVat(item.getUnitPrice(), item.getDefaultVAT())
				.compareTo(withVat(here.unitPrice == null ? BigDecimal.ZERO : BigDecimal.valueOf(here.unitPrice),
						here.defaultVat)) != 0) {
			return true;
		}
		// The import keeps the current family when the ERP's one is not at the head office: compared only when it is
		String family = item.getFamilyExternalId();
		if (family != null && familyCodes.contains(family) && !family.equals(here.familyCode)) {
			return true;
		}
		String subFamily = item.getSubFamilyExternalId();
		return subFamily != null && subFamilyCodes.contains(subFamily) && !subFamily.equals(here.subFamilyCode);
	}

	/** price x (1 + VAT/100), 3 decimals, HALF_UP. */
	static BigDecimal withVat(BigDecimal price, Integer vat) {
		BigDecimal rate = BigDecimal.ONE.add(BigDecimal.valueOf(vat == null ? 0 : vat).movePointLeft(2));
		return (price == null ? BigDecimal.ZERO : price).multiply(rate).setScale(3, RoundingMode.HALF_UP);
	}

	/** The head office item as it is, inactive. */
	private static ErpItemDTO inactiveCopy(NavPosPagesHeadOffice.Item here) {
		ErpItemDTO item = new ErpItemDTO();
		item.setExternalId(here.erpExternalId.trim());
		item.setCode(here.code);
		item.setName(here.name);
		item.setDescription(here.description);
		item.setUnitPrice(here.unitPrice == null ? null : BigDecimal.valueOf(here.unitPrice));
		item.setDefaultVAT(here.defaultVat);
		item.setItemDiscGroup(here.itemDiscGroup);
		item.setMaximumAuthorizedDiscount(here.maximumAuthorizedDiscount);
		item.setFamilyExternalId(here.familyCode);
		item.setSubFamilyExternalId(here.subFamilyCode);
		item.setActive(Boolean.FALSE);
		return item;
	}

	// ─── Barcodes ───────────────────────────────────────────────

	public NavPosRun<ErpItemBarcodeDTO> barcodes() {
		String page = properties.getPage().getBarcodes();
		Summary summary = new Summary("barcodes", page, properties.isDryRun());
		String waiting = waitingFor(ITEMS_RUN, ITEMS_PENDING, "items");
		if (waiting != null) {
			return run(new ArrayList<>(), summary.waiting(waiting), page);
		}
		Set<String> itemCodes = headOffice.items().keySet();
		List<ErpItemBarcodeDTO> handed = new ArrayList<>();

		// 1. The barcodes of the new items that arrived after the cursor started
		List<String> needing = new ArrayList<>();
		for (String key : state.keysStartingWith(NEEDS_BARCODES)) {
			String code = key.substring(NEEDS_BARCODES.length());
			if (itemCodes.contains(code)) {
				needing.add(code);
			}
		}
		summary.put("itemsNeedingBarcodes", needing.size());
		int fromList = 0;
		for (int from = 0; from < needing.size(); from += ITEMS_PER_BARCODE_CALL) {
			List<String> batch = needing.subList(from, Math.min(from + ITEMS_PER_BARCODE_CALL, needing.size()));
			List<NavPosBarcodeRow> rows = source.readBarcodesOfItems(batch);
			Classified classified = classify(rows, itemCodes, summary);
			Set<String> waitingItems = new HashSet<>();
			for (NavPosBarcodeRow row : classified.toHand) {
				waitingItems.add(row.getItemNo().trim());
			}
			fromList += classified.toHand.size();
			handed.addAll(mapper.barcodes(classified.toHand).getRows());
			for (String code : batch) {
				if (!waitingItems.contains(code) && !properties.isDryRun()) {
					state.remove(NEEDS_BARCODES + code); // every barcode of the item is at the head office
				}
			}
		}
		if (fromList > 0) {
			summary.put("handedForNewItems", fromList);
			return run(summary.handOver(handed, 0, cap()), summary, page);
		}

		// 2. The cursor on Entry_No
		long cursor = cursor();
		summary.put("cursorFrom", cursor);
		int pages = 0;
		while (true) {
			List<NavPosBarcodeRow> rows = new ArrayList<>(source.readBarcodesAfter(cursor));
			pages++;
			if (rows.isEmpty()) {
				summary.put("caughtUp", true);
				break;
			}
			rows.sort(Comparator.comparing(NavPosBarcodeRow::getEntryNo, Comparator.nullsFirst(Long::compare)));
			long highest = entryNo(rows.get(rows.size() - 1));
			if (highest <= cursor) {
				summary.guard("the ERP answered rows at or below the cursor " + cursor + ": cursor kept");
				break;
			}
			Classified classified = classify(rows, itemCodes, summary);
			if (classified.toHand.isEmpty()) {
				cursor = highest; // every row of the page is at the head office or left out
				continue;
			}
			long firstToHand = entryNo(classified.toHand.get(0));
			for (NavPosBarcodeRow row : rows) {
				if (entryNo(row) < firstToHand) {
					cursor = Math.max(cursor, entryNo(row));
				}
			}
			handed.addAll(mapper.barcodes(classified.toHand).getRows());
			break;
		}
		summary.put("pagesRead", pages);
		summary.put("cursorTo", cursor);
		if (!properties.isDryRun()) {
			state.put(BARCODE_CURSOR, String.valueOf(cursor));
		}
		return run(summary.handOver(handed, 0, cap()), summary, page);
	}

	/** The rows of a read: left out (counted), already at the head office, or to hand over (by Entry_No). */
	private Classified classify(List<NavPosBarcodeRow> rows, Set<String> itemCodes, Summary summary) {
		summary.add("read", rows.size());
		// The same barcode twice: the highest Entry_No is the one that counts
		Map<String, NavPosBarcodeRow> latest = new LinkedHashMap<>();
		for (NavPosBarcodeRow row : rows) {
			String barcode = text(row.getCrossReferenceNo(), null);
			if (barcode.isEmpty()) {
				summary.leftOut(NavPosPagesMapper.BLANK_BARCODE);
			} else if (text(row.getItemNo(), null).isEmpty()) {
				summary.leftOut(NavPosPagesMapper.BLANK_ITEM_NO);
			} else if (!itemCodes.contains(row.getItemNo().trim())) {
				summary.leftOut(ITEM_NOT_AT_HEAD_OFFICE);
			} else {
				NavPosBarcodeRow other = latest.get(barcode);
				if (other != null) {
					summary.leftOut(REPLACED_BY_LATER_ROW);
				}
				if (other == null || entryNo(row) > entryNo(other)) {
					latest.put(barcode, row);
				}
			}
		}
		Map<String, NavPosPagesHeadOffice.Barcode> here = latest.isEmpty() ? new LinkedHashMap<>()
				: headOffice.barcodes(latest.keySet());
		Classified classified = new Classified();
		for (Map.Entry<String, NavPosBarcodeRow> entry : latest.entrySet()) {
			NavPosPagesHeadOffice.Barcode saved = here.get(entry.getKey());
			if (saved != null && entry.getValue().getItemNo().trim().equals(saved.itemCode)
					&& !Boolean.FALSE.equals(saved.active)) {
				summary.add("alreadyAtHeadOffice", 1);
			} else {
				classified.toHand.add(entry.getValue());
			}
		}
		classified.toHand.sort(Comparator.comparing(NavPosBarcodeRow::getEntryNo, Comparator.nullsFirst(Long::compare)));
		return classified;
	}

	private static final class Classified {
		final List<NavPosBarcodeRow> toHand = new ArrayList<>();
	}

	private static long entryNo(NavPosBarcodeRow row) {
		return row.getEntryNo() == null ? 0 : row.getEntryNo();
	}

	private long cursor() {
		return number(state.get(BARCODE_CURSOR));
	}

	// ─── Invoices (invoices from the ERP, step a) ───────────────

	/**
	 * For each year of invoices.years, the invoices whose number starts with that year's prefix (FVV26) after the highest
	 * number the head office gives for it (highestByYear, by prefix); without one, after invoices.start-number when it is
	 * of that year, else from the first invoice of the year. At most max-per-run per year. Merged, each number once, by
	 * number. Nothing is kept here: the head office's numbers are the memory. Without years, nothing is read. Dry run:
	 * read and summarised, nothing handed.
	 */
	public NavPosRun<ErpSupplyInvoiceDTO> invoices(Map<String, String> highestByYear) {
		String page = properties.getPage().getInvoices();
		Summary summary = new Summary("invoices", page, properties.isDryRun());
		NavPosPagesProperties.Invoices settings = properties.getInvoices();
		if (settings.getYears() == null || settings.getYears().isEmpty()) {
			summary.put("notConfigured", NavPosPagesProperties.PREFIX + ".invoices.years is not set");
			summary.put("handed", 0);
			return run(new ArrayList<>(), summary, page);
		}
		Map<String, Object> years = new LinkedHashMap<>();
		List<NavPosInvoiceRow> rows = new ArrayList<>();
		for (Integer year : settings.getYears()) {
			String prefix = settings.yearPrefix(year);
			String after = readAfter(prefix, highestByYear);
			List<NavPosInvoiceRow> read = source.readInvoicesAfter(prefix, after);
			Map<String, Object> ofYear = new LinkedHashMap<>();
			ofYear.put("after", after);
			ofYear.put("read", read.size());
			years.put(prefix, ofYear);
			rows.addAll(read);
		}
		summary.put("years", years);
		NavPosResult<ErpSupplyInvoiceDTO> mapped = mapper.invoices(rows, settings.getCustomerField().trim());
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

	/** The highest number given for the prefix; else the start number when it is of that year; else null. */
	private String readAfter(String prefix, Map<String, String> highestByYear) {
		String highest = highestByYear == null ? null : highestByYear.get(prefix);
		if (highest != null && !highest.trim().isEmpty()) {
			return highest.trim();
		}
		String start = properties.getInvoices().getStartNumber();
		return start != null && start.trim().startsWith(prefix) ? start.trim() : null;
	}

	// ─── State and summary ──────────────────────────────────────

	/** Null when the previous kind ran and has nothing left to save; otherwise the reason to wait. */
	private String waitingFor(String runKey, String pendingKey, String what) {
		if (!"true".equals(state.get(runKey))) {
			return "waiting for " + what + ": no " + what + " run yet";
		}
		long pending = number(state.get(pendingKey));
		return pending > 0 ? "waiting for " + what + ": " + pending + " new " + what + " not at the head office yet" : null;
	}

	private void saveRun(String runKey, String pendingKey, int fresh) {
		if (!properties.isDryRun()) {
			state.put(runKey, "true");
			state.put(pendingKey, String.valueOf(fresh));
		}
	}

	/** Families, sub-families and barcodes: one packet per run, the rest at the next runs. */
	private int cap() {
		return properties.getPacketSize();
	}

	private <T> NavPosRun<T> run(List<T> handed, Summary summary, String page) {
		return new NavPosRun<>(properties.isDryRun() ? new ArrayList<>() : handed, summary.values, source.pageUrl(page));
	}

	private static long number(String value) {
		try {
			return value == null ? 0 : Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Trimmed text, the fallback when blank, "" when both are blank. */
	private static String text(String value, String fallback) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isEmpty() && fallback != null) {
			return fallback.trim();
		}
		return trimmed;
	}

	/** The summary of a run, in the order it is written to the communications log. */
	private static final class Summary {
		final Map<String, Object> values = new LinkedHashMap<>();
		final Map<String, Integer> leftOut = new TreeMap<>();

		Summary(String kind, String page, boolean dryRun) {
			values.put("run", kind);
			values.put("page", page);
			values.put("dryRun", dryRun);
			values.put("leftOut", leftOut);
		}

		void read(NavPosResult<?> result) {
			values.put("read", result.getRead());
			result.getLeftOut().forEach((reason, count) -> leftOut.merge(reason, count, Integer::sum));
			if (!result.getNotes().isEmpty()) {
				values.put("notes", result.getNotes());
			}
		}

		void leftOut(String reason) {
			leftOut.merge(reason, 1, Integer::sum);
		}

		void add(String key, int count) {
			Object current = values.get(key);
			values.put(key, (current instanceof Number ? ((Number) current).intValue() : 0) + count);
		}

		void put(String key, Object value) {
			values.put(key, value);
		}

		Summary waiting(String reason) {
			values.put("waiting", reason);
			values.put("handed", 0);
			return this;
		}

		void guard(String warning) {
			values.put("guard", warning);
		}

		/** The first cap changes; counts of new, handed and held back. */
		<T> List<T> handOver(List<T> changes, int fresh, int cap) {
			values.put("new", fresh);
			if (!values.containsKey("changed")) {
				values.put("changed", changes.size() - fresh);
			}
			List<T> handed = changes.size() > cap ? new ArrayList<>(changes.subList(0, cap)) : changes;
			values.put("handed", (Boolean) values.get("dryRun") ? 0 : handed.size());
			values.put("toHand", changes.size());
			values.put("heldBackByCap", changes.size() - handed.size());
			return handed;
		}
	}
}
