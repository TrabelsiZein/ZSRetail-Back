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
import java.util.stream.Collectors;

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
 * and barcode runs apply them all themselves, in packets of packet-size, each packet in its own transaction
 * ({@link NavPosPagesImport}), one run of each at a time; families and sub-families hand one packet to the import
 * (ErpItemBootstrapService, unchanged) per run. The head office tables are the memory of what was seen: a row the
 * import did not save is still different at the next run and is taken again.
 * <ul>
 * <li><b>Order.</b> Sub-families wait for the families, items for both: the waiting run reads the categories page (small)
 * and waits while a family or sub-family of the ERP is not at the head office yet. Barcodes wait for the items: each item
 * run writes, before handing over, how many items of the ERP are not at the head office yet; the barcode run waits while
 * that number is above 0 or no item run happened. The items run counts before applying, then again once every packet
 * is applied: 0 proves the new items were saved.</li>
 * <li><b>Items.</b> Compared on what the import applies: name, description, VAT, active, ERP id, family and sub-family
 * (only when the ERP's one exists at the head office), the price with VAT at 3 decimals, discount group and maximum
 * discount (the import clears them). A null or zero price makes the item inactive (the mapper). Release 2.2: the
 * items page is read once per active stock point, in list order ({@link NavPosPagesStockPoints}; none: the run does
 * nothing); every point's rows are written in full (new, changed, gone = inactive, never deleted), after the items; the
 * item gets its default copy from the first point where it is active, else the first that has it. A point that
 * answers no row keeps its rows and counts with them; a failed read fails the run before anything is applied. Items of
 * the ERP (erp_external_id set) in no point any more are handed inactive, never deleted, whatever their number; when a
 * point answered no row (a broken connection or a wrong code, never a cleanup) nothing is deactivated. Hand-made
 * items, packs made at the head office and TAX_STAMP have no ERP id and are never touched. A blank Description (the
 * name and the description) never replaces those of an item already at the head office; a new item takes its code as
 * name.</li>
 * <li><b>Barcodes.</b> One run goes on until it has caught up. A cursor on Entry_No, in the state table, read page after
 * page until the ERP answers no row. It only moves over rows that are at the head office (checked against its barcode
 * table, again after each packet), left out (blank, item not at the head office) or replaced by a later row of the same
 * barcode, and is saved after each packet: it never passes a row that was not saved (such a row stops the run, the next
 * run hands it again). New items arriving after the barcodes started are kept in the state table ("needs its
 * barcodes"); every one of them has its barcodes read by Item_No and applied first, in the same run; an item leaves
 * that list once all its barcodes are at the head office.</li>
 * <li><b>Invoices</b> (invoices from the ERP, step a): read by number per configured year, see {@link #invoices}; no
 * state kept here.</li>
 * <li><b>Dry run.</b> Reads, compares and summarises (the barcode run to the last page; the items run per stock point);
 * hands nothing; writes no row of a point; does not write the state table.</li>
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
	static final String BARCODES_ALREADY_RUNNING = "a barcode run is already running: this one does not start";

	/** Stock points, step 2: the items run reads the active points; without one it does nothing. */
	public static final String NO_STOCK_POINT = "No point de stock: create one in Catalogue > Points de stock, then run the"
			+ " job again.";

	/** The items whose family or sub-family is not among the ERP's categories named in the summary, at most. */
	static final int NOT_IN_CATEGORIES_SHOWN = 10;

	/**
	 * "family X" or "sub-family Y" when the item names one that is not among the categories the head office has from the
	 * ERP (the import then keeps the item without it, as before); null otherwise.
	 */
	static String missingCategory(ErpItemDTO item, Set<String> familyCodes, Set<String> subFamilyCodes) {
		String family = item.getFamilyExternalId();
		if (family != null && !family.trim().isEmpty() && !familyCodes.contains(family.trim())) {
			return "family " + family.trim();
		}
		String subFamily = item.getSubFamilyExternalId();
		if (subFamily != null && !subFamily.trim().isEmpty() && !subFamilyCodes.contains(subFamily.trim())) {
			return "sub-family " + subFamily.trim();
		}
		return null;
	}

	private final NavPosPagesSource source;
	private final NavPosPagesHeadOffice headOffice;
	private final NavPosPagesStockPoints stockPoints;
	private final NavPosPagesState state;
	private final NavPosPagesProperties properties;
	private final NavPosPagesImport importer;
	private final NavPosPagesMapper mapper;
	/** One items run at a time (the scheduler and "Run now" may meet). */
	private final AtomicBoolean itemsRunning = new AtomicBoolean();
	/** One barcode run at a time. */
	private final AtomicBoolean barcodesRunning = new AtomicBoolean();

	public NavPosPagesSync(NavPosPagesSource source, NavPosPagesHeadOffice headOffice, NavPosPagesStockPoints stockPoints,
			NavPosPagesState state, NavPosPagesProperties properties, NavPosPagesImport importer) {
		this.source = source;
		this.headOffice = headOffice;
		this.stockPoints = stockPoints;
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
		List<NavPosPagesStockPoints.Point> points = stockPoints.activePoints();
		if (points.isEmpty()) {
			return run(new ArrayList<>(), summary.waiting(NO_STOCK_POINT), page);
		}
		String waiting = missingFamilies(source.readCategories(), true);
		if (waiting != null) {
			return run(new ArrayList<>(), summary.waiting(waiting), page);
		}
		// Every point read first, in list order: a failed read fails the run before anything is applied
		List<PointRead> reads = new ArrayList<>();
		for (NavPosPagesStockPoints.Point point : points) {
			long start = System.nanoTime();
			List<NavPosStockRow> rows;
			try {
				rows = source.readItems(point.code);
			} catch (RuntimeException e) {
				recordRead(point, NavPosPagesStockPoints.READ_FAILED, "read failed: " + e.getMessage());
				throw e;
			}
			PointRead read = new PointRead(point, rows.size(), mapper.items(rows), millisSince(start));
			summary.addRead(read.result);
			reads.add(read);
		}
		List<String> silent = reads.stream().filter(read -> read.read == 0).map(read -> read.point.code)
				.collect(Collectors.toList());
		boolean allSilent = silent.size() == reads.size();
		Map<String, NavPosPagesHeadOffice.Item> local = headOffice.items();
		Set<String> familyCodes = headOffice.families().keySet();
		Set<String> subFamilyCodes = headOffice.subFamilies().keySet();
		String taxStamp = headOffice.taxStampErpCode();

		// The rows of each point that answered (compared before the items, whose blank names are filled below)
		for (PointRead read : reads) {
			read.stored = stockPoints.rows(read.point.id);
			if (read.read > 0) {
				read.compare(taxStamp, familyCodes, subFamilyCodes);
			}
		}

		// The item's default copy: the first point of the list where it is active, else the first that has it. A point
		// that answered no row counts with its last known rows (unless no point answered: nothing changes, as before).
		Map<String, ErpItemDTO> merged = new LinkedHashMap<>();
		for (PointRead read : reads) {
			List<ErpItemDTO> ofPoint = read.read > 0 ? read.result.getRows()
					: allSilent ? new ArrayList<>() : fromStored(read.stored);
			for (ErpItemDTO item : ofPoint) {
				ErpItemDTO first = merged.get(item.getCode());
				if (first == null || (!Boolean.TRUE.equals(first.getActive()) && Boolean.TRUE.equals(item.getActive()))) {
					merged.put(item.getCode(), item);
				}
			}
		}

		List<ErpItemDTO> changes = new ArrayList<>();
		Set<String> freshCodes = new HashSet<>();
		Set<String> inErp = new HashSet<>();
		int changed = 0;
		// An item whose family or sub-family is not among the ERP's categories is imported without it (as before): counted
		int notInCategories = 0;
		List<String> notInCategoriesFirst = new ArrayList<>();
		for (ErpItemDTO item : merged.values()) {
			inErp.add(item.getCode());
			if (item.getCode().equals(taxStamp)) {
				summary.leftOut(TAX_STAMP_ITEM); // the import never saves it: it would wait for ever
				continue;
			}
			String missing = missingCategory(item, familyCodes, subFamilyCodes);
			if (missing != null) {
				notInCategories++;
				if (notInCategoriesFirst.size() < NOT_IN_CATEGORIES_SHOWN) {
					notInCategoriesFirst.add(item.getCode() + " (" + missing + ")");
				}
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

		// Items of the ERP in no point any more: inactive, never deleted, whatever their number; not when a point answered
		// no row (a broken connection or a wrong code, never a cleanup)
		List<ErpItemDTO> gone = new ArrayList<>();
		for (NavPosPagesHeadOffice.Item here : local.values()) {
			if (here.fromErp() && here.isActive() && !here.code.equals(taxStamp) && !inErp.contains(here.code)) {
				gone.add(inactiveCopy(here));
			}
		}
		int deactivated = 0;
		if (!gone.isEmpty()) {
			if (allSilent) {
				summary.guard("the ERP answered no item for the location: " + gone.size()
						+ " items not deactivated");
			} else if (!silent.isEmpty()) {
				summary.guard("stock points that answered no item (" + String.join(", ", silent) + "): " + gone.size()
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
		if (notInCategories > 0) {
			summary.put("notInCategories", notInCategories);
			summary.put("notInCategoriesFirst", String.join(", ", notInCategoriesFirst)); // text: never a list in the log
		}
		if (properties.isDryRun()) {
			summary.put("applied", 0);
			summary.put("packets", 0);
			summary.put("stockPoints", pointSummaries(reads));
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
				String message = "Items: packet " + (from / size + 1) + " of " + packets + " failed, " + applied.size()
						+ " of " + changes.size() + " rows applied before it (the next run goes on): " + e.getMessage();
				for (PointRead read : reads) {
					recordRead(read.point, NavPosPagesStockPoints.READ_FAILED, "rows not written: " + message);
				}
				throw new IllegalStateException(message, e);
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
		// The rows of each point, after the items (a row needs its item), in packets by item code
		for (PointRead read : reads) {
			long start = System.nanoTime();
			int count = (read.toWrite.size() + size - 1) / size;
			for (int from = 0; from < read.toWrite.size(); from += size) {
				List<NavPosPagesStockPoints.Row> packet = new ArrayList<>(
						read.toWrite.subList(from, Math.min(from + size, read.toWrite.size())));
				try {
					read.written += stockPoints.write(read.point.id, packet);
				} catch (RuntimeException e) {
					String message = "Stock point " + read.point.code + ": packet " + (from / size + 1) + " of " + count
							+ " failed, " + read.written + " of " + read.toWrite.size()
							+ " rows written before it (the next run goes on): " + e.getMessage();
					recordRead(read.point, NavPosPagesStockPoints.READ_FAILED, message);
					throw new IllegalStateException(message, e);
				}
			}
			read.millis += millisSince(start);
		}
		summary.put("stockPoints", pointSummaries(reads));
		for (PointRead read : reads) {
			recordRead(read.point, read.read == 0 ? NavPosPagesStockPoints.READ_NO_ANSWER : NavPosPagesStockPoints.READ_OK,
					(read.read == 0 ? "no row: its rows kept; " : "") + "read " + read.read + ", new " + read.fresh
							+ ", changed " + read.changed + ", deactivated " + read.deactivated + ", written "
							+ read.written + ", " + read.millis + " ms");
		}
		return run(applied, summary, page);
	}

	/** Step 4: the point's last read, for the points page; never in a dry run; a failure to record is only logged. */
	private void recordRead(NavPosPagesStockPoints.Point point, String status, String summary) {
		if (properties.isDryRun()) {
			return;
		}
		try {
			stockPoints.recordRead(point.id, status, summary);
		} catch (RuntimeException e) {
			org.apache.logging.log4j.LogManager.getLogger(NavPosPagesSync.class)
					.warn("Stock point {}: last read not recorded ({})", point.code, e.getMessage());
		}
	}

	/** One point's read, its rows as the head office has them, and the rows to write. */
	private final class PointRead {
		final NavPosPagesStockPoints.Point point;
		/** Rows the ERP answered for the point (0: no answer, nothing changes in the point). */
		final int read;
		final NavPosResult<ErpItemDTO> result;
		long millis;
		Map<String, NavPosPagesStockPoints.Row> stored = new LinkedHashMap<>();
		final List<NavPosPagesStockPoints.Row> toWrite = new ArrayList<>();
		int fresh;
		int changed;
		int deactivated;
		int written;

		PointRead(NavPosPagesStockPoints.Point point, int read, NavPosResult<ErpItemDTO> result, long millis) {
			this.point = point;
			this.read = read;
			this.result = result;
			this.millis = millis;
		}

		/** New and changed rows; the active rows no longer answered, inactive (never deleted). By item code. */
		void compare(String taxStamp, Set<String> familyCodes, Set<String> subFamilyCodes) {
			Set<String> answered = new HashSet<>();
			for (ErpItemDTO item : result.getRows()) {
				answered.add(item.getCode());
				if (item.getCode().equals(taxStamp)) {
					continue; // the import never saves it
				}
				NavPosPagesStockPoints.Row here = stored.get(item.getCode());
				NavPosPagesStockPoints.Row row = rowOf(item, here, familyCodes, subFamilyCodes);
				if (here == null) {
					fresh++;
					toWrite.add(row);
				} else if (differs(row, here)) {
					changed++;
					toWrite.add(row);
				}
			}
			for (NavPosPagesStockPoints.Row here : stored.values()) {
				if (here.active && !answered.contains(here.itemCode)) {
					deactivated++;
					toWrite.add(here.inactive());
				}
			}
			toWrite.sort(Comparator.comparing(row -> row.itemCode));
		}
	}

	/**
	 * The point's row for an item of its read. A blank Description (name and description) keeps those of the row; a new
	 * row with a blank name takes the item code, as the import does for a new item. A family or sub-family of the ERP
	 * that is not at the head office keeps the row's one, as the import does for the item.
	 */
	private static NavPosPagesStockPoints.Row rowOf(ErpItemDTO item, NavPosPagesStockPoints.Row here, Set<String> familyCodes,
			Set<String> subFamilyCodes) {
		String name = text(item.getName(), null);
		if (name.isEmpty()) {
			name = here != null ? here.name : item.getCode();
		}
		String description = text(item.getDescription(), null);
		if (description.isEmpty() && here != null) {
			description = text(here.description, null);
		}
		return new NavPosPagesStockPoints.Row(item.getCode(), name, description.isEmpty() ? null : description,
				known(item.getFamilyExternalId(), familyCodes, here == null ? null : here.familyCode),
				known(item.getSubFamilyExternalId(), subFamilyCodes, here == null ? null : here.subFamilyCode),
				item.getUnitPrice() == null ? 0.0 : item.getUnitPrice().doubleValue(), Boolean.TRUE.equals(item.getActive()));
	}

	/** The ERP's code when the head office has it, else the code kept. */
	private static String known(String erpCode, Set<String> headOfficeCodes, String kept) {
		String code = blankToNull(erpCode);
		return code != null && headOfficeCodes.contains(code) ? code : blankToNull(kept);
	}

	/** Name, description, family, sub-family, active, the price with VAT at 3 decimals. */
	private boolean differs(NavPosPagesStockPoints.Row row, NavPosPagesStockPoints.Row here) {
		Integer vat = properties.getDefaultVat();
		return !text(row.name, null).equals(text(here.name, null))
				|| !text(row.description, null).equals(text(here.description, null))
				|| !Objects.equals(blankToNull(row.familyCode), blankToNull(here.familyCode))
				|| !Objects.equals(blankToNull(row.subFamilyCode), blankToNull(here.subFamilyCode))
				|| row.active != here.active || withVat(price(row), vat).compareTo(withVat(price(here), vat)) != 0;
	}

	private static BigDecimal price(NavPosPagesStockPoints.Row row) {
		return row.unitPrice == null ? BigDecimal.ZERO : BigDecimal.valueOf(row.unitPrice);
	}

	/** A point's last known rows as items of the ERP, for the merge when the point answered no row. */
	private List<ErpItemDTO> fromStored(Map<String, NavPosPagesStockPoints.Row> stored) {
		List<ErpItemDTO> items = new ArrayList<>();
		for (NavPosPagesStockPoints.Row row : stored.values()) {
			ErpItemDTO item = new ErpItemDTO();
			item.setExternalId(row.itemCode);
			item.setCode(row.itemCode);
			item.setName(row.name);
			item.setDescription(row.description);
			item.setFamilyExternalId(row.familyCode);
			item.setSubFamilyExternalId(row.subFamilyCode);
			item.setDefaultVAT(properties.getDefaultVat());
			item.setUnitPrice(price(row));
			item.setActive(row.active);
			items.add(item);
		}
		return items;
	}

	/** The stockPoints part of the summary: per point code, in list order. */
	private static Map<String, Object> pointSummaries(List<PointRead> reads) {
		Map<String, Object> points = new LinkedHashMap<>();
		for (PointRead read : reads) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("name", read.point.name);
			values.put("read", read.read);
			if (read.read == 0) {
				values.put("noAnswer", true); // its rows kept as they are
			}
			values.put("new", read.fresh);
			values.put("changed", read.changed);
			values.put("deactivated", read.deactivated);
			values.put("written", read.written);
			values.put("durationMs", read.millis);
			points.put(read.point.code, values);
		}
		return points;
	}

	private static long millisSince(long start) {
		return (System.nanoTime() - start) / 1_000_000;
	}

	private static String blankToNull(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
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

	/**
	 * One run goes on until it has caught up, applying its changes itself in packets of packet-size, each packet in its
	 * own transaction ({@link NavPosPagesImport#barcodes}): first every item of the "needs its barcodes" list, then the
	 * cursor page after page until the ERP answers no row. After each packet the head office barcode table is read again:
	 * the cursor is saved only over rows that are there, and a row the import did not save stops the run (the next run
	 * hands it again). A failed packet ends the run in error; the packets before it and the cursor saved after them stay.
	 * The rows applied are in the run, for the tests; the connector hands nothing more to the job.
	 */
	public NavPosRun<ErpItemBarcodeDTO> barcodes() {
		if (!barcodesRunning.compareAndSet(false, true)) {
			throw new ErpSyncWarningException(BARCODES_ALREADY_RUNNING);
		}
		try {
			return readAndApplyBarcodes();
		} finally {
			barcodesRunning.set(false);
		}
	}

	private NavPosRun<ErpItemBarcodeDTO> readAndApplyBarcodes() {
		String page = properties.getPage().getBarcodes();
		Summary summary = new Summary("barcodes", page, properties.isDryRun());
		String waiting = waitingFor(ITEMS_RUN, ITEMS_PENDING, "items");
		if (waiting != null) {
			summary.waiting(waiting);
			summary.values.remove("handed");
			summary.put("applied", 0);
			summary.put("packets", 0);
			summary.put("caughtUp", false);
			return run(new ArrayList<>(), summary, page);
		}
		Set<String> itemCodes = headOffice.items().keySet();
		BarcodePackets packets = new BarcodePackets();

		// 1. The barcodes of the new items that arrived after the cursor started, every item of the list
		List<String> needing = new ArrayList<>();
		for (String key : state.keysStartingWith(NEEDS_BARCODES)) {
			String code = key.substring(NEEDS_BARCODES.length());
			if (itemCodes.contains(code)) {
				needing.add(code);
			}
		}
		summary.put("itemsNeedingBarcodes", needing.size());
		int forNewItems = 0;
		for (int from = 0; from < needing.size(); from += ITEMS_PER_BARCODE_CALL) {
			List<String> batch = needing.subList(from, Math.min(from + ITEMS_PER_BARCODE_CALL, needing.size()));
			Classified classified = classify(source.readBarcodesOfItems(batch), itemCodes, summary);
			Set<String> notDone = new HashSet<>();
			for (int at = 0; at < classified.toHand.size(); at += cap()) {
				List<NavPosBarcodeRow> packet = classified.toHand.subList(at,
						Math.min(at + cap(), classified.toHand.size()));
				List<NavPosBarcodeRow> unsaved = packets.apply(packet);
				forNewItems += packet.size() - unsaved.size();
				unsaved.forEach(row -> notDone.add(row.getItemNo().trim()));
			}
			for (String code : batch) {
				if (!notDone.contains(code) && !properties.isDryRun()) {
					state.remove(NEEDS_BARCODES + code); // every barcode of the item is at the head office
				}
			}
		}
		if (forNewItems > 0) {
			summary.put("appliedForNewItems", forNewItems);
		}

		// 2. The cursor on Entry_No, page after page until the ERP answers no row
		long cursor = cursor();
		summary.put("cursorFrom", cursor);
		int pages = 0;
		boolean caughtUp = false;
		reading: while (true) {
			List<NavPosBarcodeRow> rows = new ArrayList<>(source.readBarcodesAfter(cursor));
			pages++;
			if (rows.isEmpty()) {
				caughtUp = true;
				break;
			}
			rows.sort(Comparator.comparing(NavPosBarcodeRow::getEntryNo, Comparator.nullsFirst(Long::compare)));
			long highest = entryNo(rows.get(rows.size() - 1));
			if (highest <= cursor) {
				summary.guard("the ERP answered rows at or below the cursor " + cursor + ": cursor kept");
				break;
			}
			List<NavPosBarcodeRow> toHand = classify(rows, itemCodes, summary).toHand;
			for (int at = 0; at < toHand.size(); at += cap()) {
				List<NavPosBarcodeRow> unsaved = packets.apply(toHand.subList(at, Math.min(at + cap(), toHand.size())));
				if (!unsaved.isEmpty()) {
					// Never past a row the import did not save: the next run hands it again
					cursor = passBefore(rows, cursor, entryNo(unsaved.get(0)));
					saveCursor(cursor);
					summary.guard(unsaved.size() + " barcodes handed but not at the head office after their packet: run"
							+ " stopped, cursor kept before them");
					break reading;
				}
				// Saved after each packet, only over the rows before the next row still to save
				cursor = at + cap() < toHand.size() ? passBefore(rows, cursor, entryNo(toHand.get(at + cap()))) : highest;
				saveCursor(cursor);
			}
			cursor = highest; // every row of the page is at the head office or left out
			saveCursor(cursor);
		}
		summary.put("pagesRead", pages);
		summary.put("cursorTo", cursor);
		summary.put("caughtUp", caughtUp);
		summary.put("applied", packets.applied.size());
		summary.put("packets", packets.count);
		if (properties.isDryRun()) {
			summary.put("toApply", packets.toApply);
		}
		return run(packets.applied, summary, page);
	}

	/** The highest Entry_No of the page below limit, or the cursor when higher. */
	private static long passBefore(List<NavPosBarcodeRow> rows, long cursor, long limit) {
		long passed = cursor;
		for (NavPosBarcodeRow row : rows) {
			if (entryNo(row) < limit) {
				passed = Math.max(passed, entryNo(row));
			}
		}
		return passed;
	}

	private void saveCursor(long cursor) {
		if (!properties.isDryRun()) {
			state.put(BARCODE_CURSOR, String.valueOf(cursor));
		}
	}

	/** The packets of one barcode run: applied one by one, each checked against the head office barcode table. */
	private final class BarcodePackets {
		final List<ErpItemBarcodeDTO> applied = new ArrayList<>();
		int count;
		int toApply;

		/**
		 * Applies the rows (by Entry_No) as one packet; returns those not at the head office afterwards, by Entry_No
		 * (none in a dry run, where nothing is applied).
		 */
		List<NavPosBarcodeRow> apply(List<NavPosBarcodeRow> rows) {
			if (rows.isEmpty()) {
				return new ArrayList<>();
			}
			if (properties.isDryRun()) {
				toApply += rows.size();
				return new ArrayList<>();
			}
			List<ErpItemBarcodeDTO> packet = mapper.barcodes(rows).getRows();
			try {
				importer.barcodes(packet);
			} catch (RuntimeException e) {
				throw new IllegalStateException("Barcodes: packet " + (count + 1) + " failed, " + applied.size()
						+ " rows applied before it (the next run goes on): " + e.getMessage(), e);
			}
			count++;
			Map<String, NavPosPagesHeadOffice.Barcode> here = headOffice.barcodes(
					rows.stream().map(row -> text(row.getCrossReferenceNo(), null)).collect(Collectors.toList()));
			List<NavPosBarcodeRow> unsaved = new ArrayList<>();
			for (NavPosBarcodeRow row : rows) {
				if (!isAtHeadOffice(row, here.get(text(row.getCrossReferenceNo(), null)))) {
					unsaved.add(row);
				}
			}
			for (ErpItemBarcodeDTO dto : packet) {
				if (unsaved.stream().noneMatch(row -> text(row.getCrossReferenceNo(), null).equals(dto.getBarcode()))) {
					applied.add(dto);
				}
			}
			return unsaved;
		}
	}

	/** The row's barcode is at the head office, on the row's item and active. */
	private static boolean isAtHeadOffice(NavPosBarcodeRow row, NavPosPagesHeadOffice.Barcode saved) {
		return saved != null && row.getItemNo().trim().equals(saved.itemCode) && !Boolean.FALSE.equals(saved.active);
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
			if (isAtHeadOffice(entry.getValue(), here.get(entry.getKey()))) {
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

	/** Rows per packet. Families and sub-families: one packet per run, the rest at the next runs. */
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

		/** Stock points: one more point read, its counts added to those of the points before it. */
		void addRead(NavPosResult<?> result) {
			add("read", result.getRead());
			result.getLeftOut().forEach((reason, count) -> leftOut.merge(reason, count, Integer::sum));
			if (!result.getNotes().isEmpty()) {
				@SuppressWarnings("unchecked")
				Map<String, Integer> notes = (Map<String, Integer>) values.computeIfAbsent("notes", key -> new LinkedHashMap<String, Integer>());
				result.getNotes().forEach((reason, count) -> notes.merge(reason, count, Integer::sum));
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
